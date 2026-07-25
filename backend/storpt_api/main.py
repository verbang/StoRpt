from __future__ import annotations

import asyncio
import os
from pathlib import Path
from typing import Annotated

from fastapi import BackgroundTasks, FastAPI, File, Form, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse

from .errors import BackendError, input_error, system_error
from .market import AkshareMarketDataProvider, parse_codes
from .models import ProcessInput
from .service import TaskService
from .storage import TaskWorkspace
from .worker import WorkerClient


def create_app(service: TaskService | None = None) -> FastAPI:
    app = FastAPI(title="StoRpt API", version="0.1.0")
    if service is None:
        workspace = TaskWorkspace(Path(os.getenv("STORPT_TASK_ROOT", ".storpt-tasks")))
        workspace.cleanup_stale()
        service = TaskService(
            workspace,
            WorkerClient(Path(os.getenv("STORPT_WORKER_JAR", "excel-worker.jar"))),
            AkshareMarketDataProvider(),
        )
    app.state.service = service

    @app.exception_handler(BackendError)
    async def backend_error_handler(_: Request, exc: BackendError) -> JSONResponse:
        return JSONResponse(exc.status_code, exc.as_payload(), headers={"Cache-Control": "no-store"})

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(_: Request, __: RequestValidationError) -> JSONResponse:
        error = input_error("请求字段缺失或格式无效。")
        return JSONResponse(422, error.as_payload(), headers={"Cache-Control": "no-store"})

    @app.middleware("http")
    async def disable_sensitive_caching(request: Request, call_next):
        response = await call_next(request)
        if request.url.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
            response.headers["Pragma"] = "no-cache"
        return response

    @app.get("/healthz", response_class=JSONResponse)
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/api/analyze")
    async def analyze(file: Annotated[UploadFile, File(...)]) -> dict:
        try:
            return await asyncio.wait_for(app.state.service.analyze(file), timeout=180)
        except TimeoutError as exc:
            raise system_error("SYSTEM-001", "任务超过 180 秒限制。", 504) from exc

    @app.post("/api/process")
    async def process(
        background_tasks: BackgroundTasks,
        file: Annotated[UploadFile, File(...)],
        sheet_index: Annotated[int, Form(...)],
        title_row: Annotated[int, Form(...)],
        data_start_row: Annotated[int, Form(...)],
        start_date: Annotated[str, Form(...)],
        end_date: Annotated[str, Form(...)],
        codes: Annotated[str, Form(...)],
        fill_name: Annotated[bool, Form()] = False,
        fill_ideal_buy: Annotated[bool, Form()] = False,
        fill_ideal_sell: Annotated[bool, Form()] = False,
    ) -> FileResponse:
        if min(sheet_index, title_row, data_start_row) < 0:
            raise input_error("表格坐标必须是非负整数。")
        try:
            parsed_codes = parse_codes(codes)
        except BackendError:
            raise
        request = ProcessInput(
            sheet_index, title_row, data_start_row, start_date, end_date,
            parsed_codes, fill_name, fill_ideal_buy, fill_ideal_sell,
        )
        try:
            output_path, filename, task_dir = await asyncio.wait_for(
                app.state.service.process(file, request), timeout=180
            )
        except TimeoutError as exc:
            raise system_error("SYSTEM-001", "任务超过 180 秒限制。", 504) from exc
        background_tasks.add_task(TaskWorkspace.remove, task_dir)
        media_type = "application/vnd.ms-excel" if filename.endswith(".xls") else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        return FileResponse(
            output_path,
            media_type=media_type,
            filename=filename,
            headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
            background=background_tasks,
        )

    return app


app = create_app()
