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


def auth_error(
    code: str,
    message: str,
    status_code: int = 401,
    details: dict[str, Any] | None = None,
) -> BackendError:
    titles = {
        "AUTH-001": "登录会话无效",
        "AUTH-002": "访问密码错误",
        "AUTH-003": "登录已暂时锁定",
    }
    return BackendError(
        code,
        "AUTH",
        "authentication",
        titles.get(code, "认证失败"),
        message,
        status_code,
        details,
    )


def input_error(message: str) -> BackendError:
    return BackendError("INPUT-001", "INPUT", "input", "股票代码输入无效", message)


def output_error(message: str) -> BackendError:
    return BackendError("OUTPUT-001", "OUTPUT", "output", "工作簿写入失败", message, 422)


def system_error(code: str, message: str, status_code: int = 503) -> BackendError:
    titles = {
        "SYSTEM-001": "任务超时",
        "SYSTEM-002": "任务正在运行",
        "SYSTEM-003": "认证尚未配置",
        "SYSTEM-004": "任务处理失败",
        "SYSTEM-005": "任务不可用",
    }
    title = titles.get(code, "系统错误")
    return BackendError(code, "SYSTEM", "system", title, message, status_code)
