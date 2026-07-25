from __future__ import annotations

from contextlib import contextmanager
from decimal import Decimal
from pathlib import Path
import re

from fastapi.testclient import TestClient

from storpt_api.auth import AuthManager, hash_password
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
