from __future__ import annotations

import asyncio
import os
from contextlib import asynccontextmanager, suppress
from pathlib import Path
from typing import Annotated

from fastapi import BackgroundTasks, Depends, FastAPI, File, Form, HTTPException, Request, Response, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict

from .auth import AuthManager, SESSION_COOKIE
from .errors import BackendError, auth_error, input_error, system_error
from .market import AkshareMarketDataProvider, parse_codes
from .models import ProcessInput
from .service import TaskService
from .storage import TaskWorkspace
from .worker import WorkerClient


class LoginRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    password: str


def create_app(
    service: TaskService | None = None,
    auth: AuthManager | None = None,
    static_dir: Path | None = None,
) -> FastAPI:
    if service is None:
        workspace = TaskWorkspace(Path(os.getenv("STORPT_TASK_ROOT", ".storpt-tasks")))
        workspace.cleanup_stale()
        service = TaskService(
            workspace,
            WorkerClient(Path(os.getenv("STORPT_WORKER_JAR", "excel-worker.jar"))),
            AkshareMarketDataProvider(),
        )
    if static_dir is None:
        configured = os.getenv("STORPT_STATIC_DIR")
        if configured:
            candidate = Path(configured)
            static_dir = candidate if candidate.is_dir() else None

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        async def cleanup_tasks() -> None:
            while True:
                await asyncio.sleep(60)
                service.cleanup_stale_tasks()

        cleanup = asyncio.create_task(cleanup_tasks())
        try:
            yield
        finally:
            cleanup.cancel()
            with suppress(asyncio.CancelledError):
                await cleanup

    app = FastAPI(title="StoRpt API", version="0.1.0", lifespan=lifespan)
    app.state.service = service
    app.state.auth = auth or AuthManager.from_environment()

    async def require_session(request: Request) -> None:
        app.state.auth.require_session(request.cookies.get(SESSION_COOKIE))

    @app.exception_handler(BackendError)
    async def backend_error_handler(_: Request, exc: BackendError) -> JSONResponse:
        return JSONResponse(
            content=exc.as_payload(),
            status_code=exc.status_code,
            headers={"Cache-Control": "no-store"},
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, __: RequestValidationError) -> JSONResponse:
        error = (
            auth_error("AUTH-002", "访问密码格式无效。", 422)
            if request.url.path == "/api/auth/login"
            else input_error("请求字段缺失或格式无效。")
        )
        return JSONResponse(
            content=error.as_payload(),
            status_code=422,
            headers={"Cache-Control": "no-store"},
        )

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

    @app.post("/api/auth/login")
    async def login(credentials: LoginRequest, response: Response) -> dict[str, int | str]:
        token = app.state.auth.authenticate(credentials.password)
        response.set_cookie(
            SESSION_COOKIE,
            token,
            max_age=app.state.auth.session_seconds,
            httponly=True,
            secure=True,
            samesite="strict",
            path="/",
        )
        return {"status": "success", "expiresIn": app.state.auth.session_seconds}

    @app.get("/api/auth/session", dependencies=[Depends(require_session)])
    async def session() -> dict[str, str]:
        return {"status": "success"}

    @app.post("/api/auth/logout")
    async def logout(response: Response) -> dict[str, str]:
        response.delete_cookie(
            SESSION_COOKIE,
            httponly=True,
            secure=True,
            samesite="strict",
            path="/",
        )
        return {"status": "success"}

    @app.post("/api/analyze", dependencies=[Depends(require_session)])
    async def analyze(file: Annotated[UploadFile, File(...)]) -> dict:
        try:
            return await asyncio.wait_for(app.state.service.analyze(file), timeout=180)
        except TimeoutError as exc:
            raise system_error("SYSTEM-001", "任务超过 180 秒限制。", 504) from exc

    @app.post("/api/process", dependencies=[Depends(require_session)])
    async def process(
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
    ) -> JSONResponse:
        if min(sheet_index, title_row, data_start_row) < 0:
            raise input_error("表格坐标必须是非负整数。")
        parsed_codes = parse_codes(codes)
        request = ProcessInput(
            sheet_index, title_row, data_start_row, start_date, end_date,
            parsed_codes, fill_name, fill_ideal_buy, fill_ideal_sell,
        )
        task_id = await app.state.service.start_process(file, request)
        return JSONResponse(
            {
                "status": "accepted",
                "taskId": task_id,
                "eventsUrl": f"/api/tasks/{task_id}/events",
            },
            status_code=202,
        )

    @app.get("/api/tasks/{task_id}/events", dependencies=[Depends(require_session)])
    async def task_events(task_id: str) -> StreamingResponse:
        app.state.service.require_task(task_id)
        return StreamingResponse(
            app.state.service.stream_events(task_id),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-store",
                "Pragma": "no-cache",
                "X-Accel-Buffering": "no",
            },
        )

    @app.get("/api/tasks/{task_id}/download", dependencies=[Depends(require_session)])
    async def download(task_id: str, background_tasks: BackgroundTasks) -> FileResponse:
        output_path, filename, _ = app.state.service.claim_download(task_id)
        background_tasks.add_task(app.state.service.finish_download, task_id)
        media_type = (
            "application/vnd.ms-excel"
            if filename.endswith(".xls")
            else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
        return FileResponse(
            output_path,
            media_type=media_type,
            filename=filename,
            headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
            background=background_tasks,
        )

    if static_dir is not None:
        index_html = static_dir / "index.html"
        app.mount("/", StaticFiles(directory=static_dir, html=True), name="static")

        # SPA fallback: refreshes on client-side routes resolve to index.html.
        # API routes are registered above and matched before this handler, so
        # genuine API 404s keep their structured JSON error response.
        @app.exception_handler(404)
        async def spa_fallback(request: Request, _exc: HTTPException) -> HTMLResponse:
            if request.url.path.startswith("/api/"):
                raise _exc
            if not index_html.is_file():
                raise _exc
            return HTMLResponse(index_html.read_text(encoding="utf-8"))

    return app


app = create_app()
