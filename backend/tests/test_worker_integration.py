from __future__ import annotations

import os
from decimal import Decimal
from pathlib import Path

import pytest

from storpt_api.models import MarketRow
from storpt_api.worker import WorkerClient


WORKER_JAR = os.getenv("STORPT_WORKER_JAR")


@pytest.mark.skipif(not WORKER_JAR, reason="STORPT_WORKER_JAR is only set in remote integration CI")
def test_python_client_runs_real_java_analyze_and_write(tmp_path: Path):
    repository = Path(__file__).resolve().parents[2]
    source = repository / "platform.xlsx"
    output = tmp_path / "verified.xlsx"
    client = WorkerClient(Path(WORKER_JAR).resolve(), timeout_seconds=30)

    analysis = client.analyze(source, "xlsx")

    latest = analysis.metadata["latestPeriod"]
    assert analysis.metadata["sheetName"] == "Sheet1"
    assert latest["titleRow"] == 27
    assert latest["dataStartRow"] == 29

    written = client.write(
        source,
        output,
        "xlsx",
        analysis.metadata["sheetIndex"],
        latest["titleRow"],
        latest["dataStartRow"],
        "2026.02.02",
        "2026.02.06",
        [MarketRow("000001", "平安银行", Decimal("10.25"), Decimal("11.50"))],
        True,
        True,
        True,
    )

    assert written.output_path == output.resolve()
    assert output.is_file()
    assert output.stat().st_size > 0
