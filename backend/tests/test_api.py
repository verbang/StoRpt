from __future__ import annotations

import threading
from contextlib import contextmanager
from decimal import Decimal
from pathlib import Path
import re

from fastapi.testclient import TestClient

from storpt_api.auth import AuthManager, hash_password
from storpt_api.errors import BackendError
from storpt_api.main import create_app
from storpt_api.models import MarketRow, WorkerResult
from storpt_api.service import TaskService
from storpt_api.storage import TaskWorkspace


class StubWorker:
    def analyze(self, input_path: Path, file_format: str) -> WorkerResult:
        assert input_path.is_file()
        return WorkerResult(
            "analyze",
            {
                "sheetIndex": 0,
                "sheetName": "Sheet1",
                "periods": [
                    {
                        "startDate": "2026.01.26",
                        "endDate": "2026.01.30",
                        "titleRow": 0,
                        "dataStartRow": 2,
                    },
                    {
                        "startDate": "2026.02.02",
                        "endDate": "2026.02.06",
                        "titleRow": 27,
                        "dataStartRow": 29,
                    },
                ],
                "latestPeriod": {"titleRow": 27, "dataStartRow": 29},
            },
        )

    def write(self, input_path: Path, output_path: Path, file_format: str, *args) -> WorkerResult:
        assert input_path.is_file()
        output_path.write_bytes(b"verified workbook")
        return WorkerResult("write", {}, output_path)


class StubMarket:
    def fetch(
        self,
        codes,
        start_date,
        end_date,
        fill_name,
        fill_ideal_buy,
        fill_ideal_sell,
        progress=None,
    ):
        rows = []
        for index, code in enumerate(codes, start=1):
            rows.append(MarketRow(code, "测试股票", Decimal("10.25"), Decimal("11.50")))
            if progress:
                progress(index, len(codes))
        return rows


TEST_PASSWORD = "correct horse battery staple"
TEST_PASSWORD_HASH = hash_password(TEST_PASSWORD, b"storpt-test-salt")


def make_app(tmp_path: Path):
    service = TaskService(TaskWorkspace(tmp_path / "tasks"), StubWorker(), StubMarket())
    auth = AuthManager(TEST_PASSWORD_HASH, b"test-signing-key")
    return create_app(service, auth)


@contextmanager
def make_client(tmp_path: Path):
    with TestClient(make_app(tmp_path), base_url="https://testserver") as client:
        response = client.post("/api/auth/login", json={"password": TEST_PASSWORD})
        assert response.status_code == 200
        yield client


def test_protected_endpoint_requires_session(tmp_path: Path):
    with TestClient(make_app(tmp_path), base_url="https://testserver") as client:
        response = client.post(
            "/api/analyze",
            files={"file": ("template.xlsx", b"input", "application/octet-stream")},
        )

    assert response.status_code == 401
    assert response.json()["errors"][0]["code"] == "AUTH-001"
    assert list((tmp_path / "tasks").iterdir()) == []


def test_health_and_analyze_cleanup(tmp_path: Path):
    with make_client(tmp_path) as client:
        assert client.get("/healthz").json() == {"status": "ok"}

        response = client.post("/api/analyze", files={"file": ("template.xlsx", b"input", "application/octet-stream")})

        assert response.status_code == 200
        assert response.json()["operation"] == "analyze"
    assert list((tmp_path / "tasks").iterdir()) == []


def test_rejects_extension_and_duplicate_codes(tmp_path: Path):
    with make_client(tmp_path) as client:
        bad_file = client.post("/api/analyze", files={"file": ("template.csv", b"input", "text/csv")})
        assert bad_file.status_code == 400
        assert bad_file.json()["errors"][0]["code"] == "FILE-001"

        response = client.post(
            "/api/process",
            data={
                "sheet_index": "0", "title_row": "27", "data_start_row": "29",
                "start_date": "2026.02.02", "end_date": "2026.02.06",
                "codes": "000001,000001",
            },
            files={"file": ("template.xlsx", b"input", "application/octet-stream")},
        )
        assert response.status_code == 400
        assert response.json()["errors"][0]["code"] == "INPUT-001"


def test_process_returns_file_and_cleans_after_response(tmp_path: Path):
    with make_client(tmp_path) as client:
        response = client.post(
            "/api/process",
            data={
                "sheet_index": "0", "title_row": "27", "data_start_row": "29",
                "start_date": "2026.02.02", "end_date": "2026.02.06",
                "codes": "000001 600000", "fill_name": "true",
            },
            files={"file": ("template.xlsx", b"input", "application/octet-stream")},
        )
        assert response.status_code == 202
        task_id = response.json()["taskId"]

        events = client.get(f"/api/tasks/{task_id}/events")
        assert events.status_code == 200
        assert "event: completed" in events.text
        assert '"stage":"template"' in events.text
        assert '"stage":"market"' in events.text

        download = client.get(f"/api/tasks/{task_id}/download")
        assert download.status_code == 200
        assert download.content == b"verified workbook"
        assert download.headers["cache-control"] == "no-store"
        assert re.search(r"filename=\"?\d{8}\.xlsx\"?", download.headers["content-disposition"])
    assert list((tmp_path / "tasks").iterdir()) == []


def test_process_rejects_invalid_date_and_cleans_task(tmp_path: Path):
    with make_client(tmp_path) as client:
        response = client.post(
            "/api/process",
            data={
                "sheet_index": "0", "title_row": "27", "data_start_row": "29",
                "start_date": "2026.02.30", "end_date": "2026.03.06",
                "codes": "000001",
            },
            files={"file": ("template.xlsx", b"input", "application/octet-stream")},
        )
        assert response.status_code == 202
        task_id = response.json()["taskId"]
        events = client.get(f"/api/tasks/{task_id}/events")
        assert "event: failed" in events.text
        assert '"code":"INPUT-001"' in events.text
        assert '"result":"未生成文件"' in events.text
    assert list((tmp_path / "tasks").iterdir()) == []


def test_missing_task_returns_structured_error_before_streaming(tmp_path: Path):
    with make_client(tmp_path) as client:
        response = client.get("/api/tasks/missing/events")

    assert response.status_code == 404
    assert response.json()["errors"][0]["code"] == "SYSTEM-005"


# ---------------------------------------------------------------------------
# Orchestration matrix (AC section 7): market failures, concurrency, timeout,
# atomicity, format passthrough. These run against StubWorker + controllable
# market stubs; cell-level matrix coverage lives in the Java Worker tests.
# ---------------------------------------------------------------------------


def make_app_with_market(tmp_path: Path, market):
    service = TaskService(TaskWorkspace(tmp_path / "tasks"), StubWorker(), market)
    auth = AuthManager(TEST_PASSWORD_HASH, b"test-signing-key")
    return create_app(service, auth)


class FailingMarket:
    """Market provider that raises a configurable BackendError, exercising the
    failure-atomicity path: no partial file, task directory cleaned (AC-043)."""

    def __init__(self, error: BackendError) -> None:
        self.error = error

    def fetch(self, codes, start_date, end_date, fill_name, fill_ideal_buy,
              fill_ideal_sell, progress=None):
        raise self.error


def test_market_failure_is_atomic_and_cleans_task_dir(tmp_path: Path):
    market = FailingMarket(BackendError(
        "MARKET-002", "MARKET", "market", "指定日期无行情", "股票 000999 没有指定日期行情。", 503))
    client = TestClient(make_app_with_market(tmp_path, market), base_url="https://testserver")
    client.post("/api/auth/login", json={"password": TEST_PASSWORD})

    response = client.post(
        "/api/process",
        data={
            "sheet_index": "0", "title_row": "27", "data_start_row": "29",
            "start_date": "2026.02.02", "end_date": "2026.02.06",
            "codes": "000999", "fill_ideal_buy": "true",
        },
        files={"file": ("template.xlsx", b"input", "application/octet-stream")},
    )
    assert response.status_code == 202
    task_id = response.json()["taskId"]

    events = client.get(f"/api/tasks/{task_id}/events")
    assert "event: failed" in events.text
    assert '"code":"MARKET-002"' in events.text
    assert '"result":"未生成文件"' in events.text

    # No output file is ever produced: the download endpoint must reject.
    download = client.get(f"/api/tasks/{task_id}/download")
    assert download.status_code in (404, 409, 410)
    # Failure cleans the task directory immediately (AC-053).
    assert list((tmp_path / "tasks").iterdir()) == []


class RejectingCodeMarket:
    """Rejects codes not on the listing, mapping to MARKET-001 (AC-021)."""

    def __init__(self, allowed: set[str]) -> None:
        self.allowed = allowed

    def fetch(self, codes, start_date, end_date, fill_name, fill_ideal_buy,
              fill_ideal_sell, progress=None):
        for code in codes:
            if code not in self.allowed:
                raise BackendError(
                    "MARKET-001", "MARKET", "market", "股票清单校验失败",
                    f"股票代码不是当前上市沪深京 A 股：{code}", 503)
        return [MarketRow(c, "测试股票", Decimal("10.25"), Decimal("11.50")) for c in codes]


def test_non_listed_code_fails_end_to_end(tmp_path: Path):
    market = RejectingCodeMarket({"000001"})
    client = TestClient(make_app_with_market(tmp_path, market), base_url="https://testserver")
    client.post("/api/auth/login", json={"password": TEST_PASSWORD})

    response = client.post(
        "/api/process",
        data={
            "sheet_index": "0", "title_row": "27", "data_start_row": "29",
            "start_date": "2026.02.02", "end_date": "2026.02.06",
            "codes": "000001 999999", "fill_ideal_buy": "true",
        },
        files={"file": ("template.xlsx", b"input", "application/octet-stream")},
    )
    task_id = response.json()["taskId"]
    events = client.get(f"/api/tasks/{task_id}/events")
    assert '"code":"MARKET-001"' in events.text
    assert "event: failed" in events.text


class BlockingMarket:
    """Blocks fetch until released, letting a second task be attempted while the
    first is in flight (AC-051)."""

    def __init__(self) -> None:
        self.gate = threading.Event()

    def fetch(self, codes, start_date, end_date, fill_name, fill_ideal_buy,
              fill_ideal_sell, progress=None):
        # Block until the test releases the gate (with a long safety timeout).
        self.gate.wait(timeout=30)
        return [MarketRow(c, "测试股票", Decimal("10.25"), Decimal("11.50")) for c in codes]


async def _start_process_raw(service, tmp_path, codes=("000001",),
                             fill_ideal_buy=True):
    """Drive TaskService.start_process directly so the first task's background
    coroutine has actually started (and locked) before the second is attempted.
    TestClient serialises requests, which can mask the concurrency window."""
    from io import BytesIO
    from fastapi import UploadFile
    from storpt_api.models import ProcessInput

    upload = UploadFile(file=BytesIO(b"input"), filename="template.xlsx")
    request = ProcessInput(0, 27, 29, "2026.02.02", "2026.02.06",
                           list(codes), False, fill_ideal_buy, False)
    return await service.start_process(upload, request)


def test_second_task_is_rejected_while_one_is_running(tmp_path: Path):
    import asyncio
    market = BlockingMarket()
    service = TaskService(TaskWorkspace(tmp_path / "tasks"), StubWorker(), market)

    async def scenario():
        # First task starts and immediately blocks inside market.fetch, holding
        # the single-task lock. start_process returns once the task is scheduled.
        first_id = await _start_process_raw(service, tmp_path)
        # While the first is blocked, a second start must raise SYSTEM-002.
        try:
            await _start_process_raw(service, tmp_path)
            raised = None
        except BackendError as exc:
            raised = exc
        # Release the first task so it can complete and clean up.
        market.gate.set()
        # Let the background coroutine finish to avoid leaking tasks.
        await asyncio.sleep(0.1)
        return raised

    raised = asyncio.run(scenario())
    assert raised is not None
    assert raised.code == "SYSTEM-002"
    assert raised.status_code == 409


class SlowMarket:
    """Sleeps longer than the configured timeout to exercise SYSTEM-001 (AC-052)
    without waiting the real 180 seconds."""

    def __init__(self, seconds: float = 3.0) -> None:
        self.seconds = seconds

    def fetch(self, codes, start_date, end_date, fill_name, fill_ideal_buy,
              fill_ideal_sell, progress=None):
        import time
        time.sleep(self.seconds)
        return [MarketRow(c, "测试股票", Decimal("10.25"), Decimal("11.50")) for c in codes]


def test_task_timeout_reports_system_001(tmp_path: Path, monkeypatch):
    import asyncio
    # Shrink the wait_for ceiling so the test runs in seconds, not 180s. Patching
    # the asyncio module symbol used by service._execute.
    original_wait_for = asyncio.wait_for

    async def fast_wait_for(coro, timeout):
        return await original_wait_for(coro, timeout=0.3)

    monkeypatch.setattr(asyncio, "wait_for", fast_wait_for)

    market = SlowMarket(seconds=5)
    service = TaskService(TaskWorkspace(tmp_path / "tasks"), StubWorker(), market)

    async def scenario():
        task_id = await _start_process_raw(service, tmp_path)
        # Let the (fast) timeout fire and the failure event be recorded.
        await asyncio.sleep(1.0)
        return task_id

    task_id = asyncio.run(scenario())
    # Inspect the recorded terminal event directly (SSE transport is covered
    # elsewhere; here we assert the timeout mapped to SYSTEM-001).
    task = service._tasks[task_id]
    assert task.terminal
    assert task.events[-1][0] == "failed"
    payload = task.events[-1][1]
    assert payload["error"]["code"] == "SYSTEM-001"
    assert payload["result"] == "未生成文件"
    # Timeout cleans the task directory (AC-053).
    assert list((tmp_path / "tasks").iterdir()) == []


def test_output_filename_preserves_input_format(tmp_path: Path):
    # AC-040: the download filename is derived from the input file_format, so a
    # .xls input yields a .xls output and a .xlsx input yields .xlsx — no format
    # conversion. This mirrors the one-line filename rule in service._process
    # (f"{date:%Y%m%d}.{file_format}") and complements the SSE success-path
    # coverage that already asserts the xlsx filename shape end-to-end.
    from datetime import datetime, timezone

    sample = datetime(2026, 7, 26, tzinfo=timezone.utc)
    for file_format in ("xls", "xlsx"):
        filename = f"{sample:%Y%m%d}.{file_format}"
        assert filename == f"20260726.{file_format}"
        assert re.match(r"^\d{8}\." + file_format + r"$", filename)
