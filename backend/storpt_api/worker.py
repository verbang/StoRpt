from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any, Literal

from .errors import BackendError, output_error, system_error
from .models import MarketRow, WorkerResult


class WorkerClient:
    """Calls the local shaded Java Worker through stdin/stdout JSON."""

    def __init__(self, jar_path: Path, java_bin: str = "java", timeout_seconds: float = 175.0):
        self.jar_path = jar_path
        self.java_bin = java_bin
        self.timeout_seconds = timeout_seconds

    def _invoke(self, request: dict[str, Any]) -> dict[str, Any]:
        try:
            completed = subprocess.run(
                [self.java_bin, "-jar", str(self.jar_path)],
                input=json.dumps(request, ensure_ascii=False),
                text=True,
                capture_output=True,
                timeout=self.timeout_seconds,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise system_error("SYSTEM-001", "Java Worker 超过任务时限。", 504) from exc
        except OSError as exc:
            raise system_error("SYSTEM-001", "Java Worker 不可用。") from exc

        try:
            response = json.loads(completed.stdout)
        except json.JSONDecodeError as exc:
            raise output_error("Java Worker 返回了无法解析的响应。") from exc
        if not isinstance(response, dict):
            raise output_error("Java Worker 响应格式无效。")
        if completed.returncode != 0 or response.get("status") != "success":
            error = (response.get("errors") or [{}])[0]
            if isinstance(error, dict) and error.get("code"):
                raise BackendError(
                    str(error["code"]),
                    str(error.get("category", "OUTPUT")),
                    str(error.get("stage", "output")),
                    str(error.get("title", "工作簿处理失败")),
                    str(error.get("message", "Java Worker 处理失败。")),
                    422,
                )
            raise output_error("Java Worker 处理失败。")
        return response

    def analyze(self, input_path: Path, file_format: Literal["xls", "xlsx"]) -> WorkerResult:
        response = self._invoke({
            "operation": "analyze",
            "inputPath": str(input_path),
            "format": file_format,
        })
        return WorkerResult("analyze", response["metadata"])

    def write(
        self,
        input_path: Path,
        output_path: Path,
        file_format: Literal["xls", "xlsx"],
        sheet_index: int,
        title_row: int,
        data_start_row: int,
        start_date: str,
        end_date: str,
        rows: list[MarketRow],
        fill_name: bool,
        fill_ideal_buy: bool,
        fill_ideal_sell: bool,
    ) -> WorkerResult:
        response = self._invoke({
            "operation": "write",
            "inputPath": str(input_path),
            "outputPath": str(output_path),
            "format": file_format,
            "sheetIndex": sheet_index,
            "latestPeriod": {"titleRow": title_row, "dataStartRow": data_start_row},
            "changes": {
                "startDate": start_date,
                "endDate": end_date,
                "rows": [row.as_worker_row() for row in rows],
                "fillName": fill_name,
                "fillIdealBuy": fill_ideal_buy,
                "fillIdealSell": fill_ideal_sell,
            },
        })
        return WorkerResult("write", response["metadata"], Path(response["outputPath"]))

