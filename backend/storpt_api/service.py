from __future__ import annotations

import asyncio
from datetime import date
from datetime import datetime
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

from fastapi import UploadFile

from .errors import BackendError, output_error, system_error
from .market import MarketDataProvider
from .models import ProcessInput
from .storage import TaskWorkspace, save_upload
from .worker import WorkerClient


def _parse_date(value: str, field: str) -> date:
    try:
        if len(value) != 10 or value[4] != "." or value[7] != ".":
            raise ValueError
        parsed = date.fromisoformat(value.replace(".", "-"))
    except (TypeError, ValueError) as exc:
        raise BackendError("INPUT-001", "INPUT", "input", "股票代码输入无效", f"{field} 必须是有效的 yyyy.MM.dd 日期。") from exc
    return parsed


class TaskService:
    def __init__(self, workspace: TaskWorkspace, worker: WorkerClient, market: MarketDataProvider):
        self.workspace = workspace
        self.worker = worker
        self.market = market
        self._busy = False

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

    async def process(self, upload: UploadFile, request: ProcessInput) -> tuple[Path, str, Path]:
        self._start()
        task_dir: Path | None = None
        published = False
        try:
            task_dir = self.workspace.create()
            suffix = Path(upload.filename or "").suffix.lower()
            input_path, file_format, _ = await save_upload(upload, task_dir / f"input{suffix}")
            start_date = _parse_date(request.start_date, "开始日期")
            end_date = _parse_date(request.end_date, "结束日期")
            if start_date > end_date:
                raise BackendError(
                    "INPUT-001", "INPUT", "input", "股票代码输入无效",
                    "开始日期不能晚于结束日期。",
                )
            rows = await asyncio.to_thread(
                self.market.fetch,
                request.codes,
                start_date,
                end_date,
                request.fill_name,
                request.fill_ideal_buy,
                request.fill_ideal_sell,
            )
            output_path = task_dir / f"output.{file_format}"
            result = await asyncio.to_thread(
                self.worker.write,
                input_path,
                output_path,
                file_format,
                request.sheet_index,
                request.title_row,
                request.data_start_row,
                request.start_date,
                request.end_date,
                rows,
                request.fill_name,
                request.fill_ideal_buy,
                request.fill_ideal_sell,
            )
            if result.output_path is None or not result.output_path.is_file():
                raise output_error("Worker 未生成可下载文件。")
            filename = f"{datetime.now(ZoneInfo('Asia/Shanghai')):%Y%m%d}.{file_format}"
            published = True
            return result.output_path, filename, task_dir
        finally:
            if not published and task_dir is not None:
                self.workspace.remove(task_dir)
            self._finish()
