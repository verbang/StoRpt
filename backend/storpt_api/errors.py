from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(slots=True)
class BackendError(Exception):
    code: str
    category: str
    stage: str
    title: str
    message: str
    status_code: int = 400
    details: dict[str, Any] | None = None

    def __post_init__(self) -> None:
        Exception.__init__(self, self.message)

    def as_payload(self) -> dict[str, Any]:
        error: dict[str, Any] = {
            "code": self.code,
            "category": self.category,
            "stage": self.stage,
            "title": self.title,
            "message": self.message,
        }
        if self.details:
            error.update(self.details)
        return {"status": "error", "errors": [error]}


def file_error(code: str, message: str, status_code: int = 400) -> BackendError:
    title = "文件超过大小限制" if code == "FILE-002" else "不支持的文件格式"
    return BackendError(code, "FILE", "upload", title, message, status_code)


def input_error(message: str) -> BackendError:
    return BackendError("INPUT-001", "INPUT", "input", "股票代码输入无效", message)


def output_error(message: str) -> BackendError:
    return BackendError("OUTPUT-001", "OUTPUT", "output", "工作簿写入失败", message, 422)


def system_error(code: str, message: str, status_code: int = 503) -> BackendError:
    title = "任务超时" if code == "SYSTEM-001" else "任务正在运行"
    return BackendError(code, "SYSTEM", "system", title, message, status_code)

