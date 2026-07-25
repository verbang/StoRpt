from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import re

from fastapi.testclient import TestClient

from storpt_api.main import create_app
from storpt_api.models import MarketRow, WorkerResult
from storpt_api.service import TaskService
from storpt_api.storage import TaskWorkspace


class StubWorker:
    def analyze(self, input_path: Path, file_format: str) -> WorkerResult:
        assert input_path.is_file()
        return WorkerResult("analyze", {"sheetName": "Sheet1", "latestPeriod": {"titleRow": 27}})

    def write(self, input_path: Path, output_path: Path, file_format: str, *args) -> WorkerResult:
        assert input_path.is_file()
        output_path.write_bytes(b"verified workbook")
        return WorkerResult("write", {}, output_path)


class StubMarket:
    def fetch(self, codes, start_date, end_date, fill_name, fill_ideal_buy, fill_ideal_sell):
        return [MarketRow(code, "测试股票", Decimal("10.25"), Decimal("11.50")) for code in codes]


def make_client(tmp_path: Path) -> TestClient:
    service = TaskService(TaskWorkspace(tmp_path / "tasks"), StubWorker(), StubMarket())
    return TestClient(create_app(service))


def test_health_and_analyze_cleanup(tmp_path: Path):
    client = make_client(tmp_path)
    assert client.get("/healthz").json() == {"status": "ok"}

    response = client.post("/api/analyze", files={"file": ("template.xlsx", b"input", "application/octet-stream")})

    assert response.status_code == 200
    assert response.json()["operation"] == "analyze"
    assert list((tmp_path / "tasks").iterdir()) == []


def test_rejects_extension_and_duplicate_codes(tmp_path: Path):
    client = make_client(tmp_path)
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
    client = make_client(tmp_path)
    response = client.post(
        "/api/process",
        data={
            "sheet_index": "0", "title_row": "27", "data_start_row": "29",
            "start_date": "2026.02.02", "end_date": "2026.02.06",
            "codes": "000001 600000", "fill_name": "true",
        },
        files={"file": ("template.xlsx", b"input", "application/octet-stream")},
    )
    assert response.status_code == 200
    assert response.content == b"verified workbook"
    assert response.headers["cache-control"] == "no-store"
    assert re.search(r"filename=\"?\d{8}\.xlsx\"?", response.headers["content-disposition"])
    assert list((tmp_path / "tasks").iterdir()) == []


def test_process_rejects_invalid_date_and_cleans_task(tmp_path: Path):
    client = make_client(tmp_path)
    response = client.post(
        "/api/process",
        data={
            "sheet_index": "0", "title_row": "27", "data_start_row": "29",
            "start_date": "2026.02.30", "end_date": "2026.03.06",
            "codes": "000001",
        },
        files={"file": ("template.xlsx", b"input", "application/octet-stream")},
    )
    assert response.status_code == 400
    assert response.json()["errors"][0]["code"] == "INPUT-001"
    assert list((tmp_path / "tasks").iterdir()) == []
