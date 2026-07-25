from __future__ import annotations

import asyncio
import json
import secrets
import time
from dataclasses import dataclass, field
from datetime import date, datetime
from pathlib import Path
from typing import Any, AsyncIterator
from zoneinfo import ZoneInfo

from fastapi import UploadFile

from .errors import BackendError, input_error, output_error, system_error
from .market import MarketDataProvider
from .models import ProcessInput
from .storage import TaskWorkspace, save_upload
from .worker import WorkerClient


TASK_TTL_SECONDS = 10 * 60


def _parse_date(value: str, field: str) -> date:
    try:
        if len(value) != 10 or value[4] != "." or value[7] != ".":
            raise ValueError
        parsed = date.fromisoformat(value.replace(".", "-"))
    except (TypeError, ValueError) as exc:
        raise input_error(f"{field} 必须是有效的 yyyy.MM.dd 日期。") from exc
    return parsed


@dataclass(slots=True)
class ProcessTask:
    task_id: str
    task_dir: Path
    input_path: Path
    file_format: str
    request: ProcessInput
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    events: list[tuple[str, dict[str, Any]]] = field(default_factory=list)
    condition: asyncio.Condition = field(default_factory=asyncio.Condition)
    terminal: bool = False
    output_path: Path | None = None
    filename: str | None = None
    download_claimed: bool = False
    runner: asyncio.Task[None] | None = None


class TaskService:
    def __init__(self, workspace: TaskWorkspace, worker: WorkerClient, market: MarketDataProvider):
        self.workspace = workspace
        self.worker = worker
        self.market = market
        self._busy = False
        self._tasks: dict[str, ProcessTask] = {}

    def _start(self) -> None:
        if self._busy:
            raise system_error("SYSTEM-002", "已有任务正在运行，请稍后再试。", 409)
        self._busy = True

    def _finish(self) -> None:
        self._busy = False

    async def analyze(self, upload: UploadFile) -> dict[str, Any]:
        self._start()
        task_dir: Path | None = None
        try:
            task_dir = self.workspace.create()
            suffix = Path(upload.filename or "").suffix.lower()
            input_path, file_format, size = await save_upload(upload, task_dir / f"input{suffix}")
            result = await asyncio.to_thread(self.worker.analyze, input_path, file_format)
            return {
                "status": "success",
                "operation": "analyze",
                "file": {"size": size, "format": file_format},
                "metadata": result.metadata,
            }
        finally:
            if task_dir is not None:
                self.workspace.remove(task_dir)
            self._finish()

    async def start_process(self, upload: UploadFile, request: ProcessInput) -> str:
        self.cleanup_stale_tasks()
        self._start()
        task_dir: Path | None = None
        try:
            task_dir = self.workspace.create()
            suffix = Path(upload.filename or "").suffix.lower()
            input_path, file_format, _ = await save_upload(upload, task_dir / f"input{suffix}")
            task_id = secrets.token_urlsafe(18)
            task = ProcessTask(task_id, task_dir, input_path, file_format, request)
            self._tasks[task_id] = task
            await self._emit(task, "progress", 10, "upload", "文件上传完成。")
            task.runner = asyncio.create_task(self._execute(task))
            return task_id
        except Exception:
            if task_dir is not None:
                self.workspace.remove(task_dir)
            self._finish()
            raise

    async def _execute(self, task: ProcessTask) -> None:
        try:
            await asyncio.wait_for(self._process(task), timeout=180)
        except TimeoutError:
            await self._fail(task, system_error("SYSTEM-001", "任务超过 180 秒限制。", 504))
        except BackendError as exc:
            await self._fail(task, exc)
        except Exception:
            await self._fail(task, system_error("SYSTEM-004", "任务处理失败。"))
        finally:
            self._finish()

    async def _process(self, task: ProcessTask) -> None:
        await self._emit(task, "progress", 15, "template", "正在校验模板和时间段。")
        analysis = await asyncio.to_thread(
            self.worker.analyze,
            task.input_path,
            task.file_format,
        )
        start_date, end_date = self._validate_process_input(analysis.metadata, task.request)
        await self._emit(task, "progress", 25, "input", "模板校验完成，正在校验股票代码。")

        loop = asyncio.get_running_loop()
        progress_futures: list[Any] = []

        def report_market(completed: int, total: int) -> None:
            progress = 35 + int(45 * completed / max(total, 1))
            progress_futures.append(asyncio.run_coroutine_threadsafe(
                self._emit(
                    task,
                    "progress",
                    progress,
                    "market",
                    f"已完成 {completed}/{total} 只股票的行情校验。",
                ),
                loop,
            ))

        await self._emit(task, "progress", 35, "market", "正在查询股票名称和历史行情。")
        try:
            rows = await asyncio.to_thread(
                self.market.fetch,
                task.request.codes,
                start_date,
                end_date,
                task.request.fill_name,
                task.request.fill_ideal_buy,
                task.request.fill_ideal_sell,
                report_market,
            )
        finally:
            for future in progress_futures:
                await asyncio.wrap_future(future)
        await self._emit(task, "progress", 80, "write", "行情校验完成，正在写入允许的单元格。")
        output_path = task.task_dir / f"output.{task.file_format}"
        result = await asyncio.to_thread(
            self.worker.write,
            task.input_path,
            output_path,
            task.file_format,
            task.request.sheet_index,
            task.request.title_row,
            task.request.data_start_row,
            task.request.start_date,
            task.request.end_date,
            rows,
            task.request.fill_name,
            task.request.fill_ideal_buy,
            task.request.fill_ideal_sell,
        )
        await self._emit(task, "progress", 98, "verify", "正在完成输出文件自检。")
        if result.output_path is None or not result.output_path.is_file():
            raise output_error("Worker 未生成可下载文件。")
        task.output_path = result.output_path
        task.filename = f"{datetime.now(ZoneInfo('Asia/Shanghai')):%Y%m%d}.{task.file_format}"
        await self._emit(
            task,
            "completed",
            100,
            "complete",
            "文件生成完成，可以下载。",
            {
                "downloadUrl": f"/api/tasks/{task.task_id}/download",
                "filename": task.filename,
            },
            terminal=True,
        )

    @staticmethod
    def _validate_process_input(
        metadata: dict[str, Any],
        request: ProcessInput,
    ) -> tuple[date, date]:
        start_date = _parse_date(request.start_date, "开始日期")
        end_date = _parse_date(request.end_date, "结束日期")
        if start_date > end_date:
            raise input_error("开始日期不能晚于结束日期。")
        latest = metadata.get("latestPeriod")
        if not isinstance(latest, dict) or any(
            latest.get(field) != expected
            for field, expected in (
                ("titleRow", request.title_row),
                ("dataStartRow", request.data_start_row),
            )
        ) or metadata.get("sheetIndex") != request.sheet_index:
            raise input_error("文件模板或目标区域已变化，请重新选择文件。")
        periods = metadata.get("periods")
        if isinstance(periods, list) and len(periods) > 1:
            previous = periods[-2]
            if isinstance(previous, dict):
                previous_start = _parse_date(previous.get("startDate"), "上一时间段开始日期")
                previous_end = _parse_date(previous.get("endDate"), "上一时间段结束日期")
                if start_date <= previous_start or end_date <= previous_end:
                    raise input_error("开始日期和结束日期必须分别晚于上一时间段。")
        return start_date, end_date

    async def _fail(self, task: ProcessTask, error: BackendError) -> None:
        self.workspace.remove(task.task_dir)
        await self._emit(
            task,
            "failed",
            task.events[-1][1]["progress"] if task.events else 0,
            error.stage,
            error.message,
            {"error": error.as_payload()["errors"][0], "result": "未生成文件"},
            terminal=True,
        )

    async def _emit(
        self,
        task: ProcessTask,
        event_name: str,
        progress: int,
        stage: str,
        message: str,
        extra: dict[str, Any] | None = None,
        terminal: bool = False,
    ) -> None:
        async with task.condition:
            payload: dict[str, Any] = {
                "sequence": len(task.events) + 1,
                "taskId": task.task_id,
                "status": "success" if event_name == "completed" else "error" if event_name == "failed" else "running",
                "stage": stage,
                "progress": progress,
                "message": message,
            }
            if extra:
                payload.update(extra)
            task.events.append((event_name, payload))
            task.updated_at = time.time()
            task.terminal = terminal
            task.condition.notify_all()

    async def stream_events(self, task_id: str) -> AsyncIterator[str]:
        task = self._task(task_id)
        index = 0
        while True:
            pending: list[tuple[str, dict[str, Any]]]
            terminal: bool
            keep_alive = False
            async with task.condition:
                if index >= len(task.events) and not task.terminal:
                    try:
                        await asyncio.wait_for(task.condition.wait(), timeout=15)
                    except TimeoutError:
                        keep_alive = True
                pending = task.events[index:]
                index = len(task.events)
                terminal = task.terminal
            if keep_alive:
                yield ": keep-alive\n\n"
                continue
            for event_name, payload in pending:
                data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
                yield f"id: {payload['sequence']}\nevent: {event_name}\ndata: {data}\n\n"
            if terminal and index >= len(task.events):
                return

    def require_task(self, task_id: str) -> None:
        self._task(task_id)

    def claim_download(self, task_id: str) -> tuple[Path, str, Path]:
        task = self._task(task_id)
        if not task.terminal or task.output_path is None or task.filename is None:
            raise system_error("SYSTEM-005", "文件尚未生成。", 409)
        if task.download_claimed:
            raise system_error("SYSTEM-005", "下载已开始或文件已过期。", 410)
        task.download_claimed = True
        return task.output_path, task.filename, task.task_dir

    def finish_download(self, task_id: str) -> None:
        task = self._tasks.pop(task_id, None)
        if task is not None:
            self.workspace.remove(task.task_dir)

    def cleanup_stale_tasks(self) -> None:
        cutoff = time.time() - TASK_TTL_SECONDS
        for task_id, task in list(self._tasks.items()):
            if task.updated_at < cutoff:
                if task.runner is not None and not task.runner.done():
                    task.runner.cancel()
                self.workspace.remove(task.task_dir)
                self._tasks.pop(task_id, None)

    def _task(self, task_id: str) -> ProcessTask:
        self.cleanup_stale_tasks()
        task = self._tasks.get(task_id)
        if task is None:
            raise system_error("SYSTEM-005", "任务不存在或已过期。", 404)
        return task
