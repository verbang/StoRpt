from __future__ import annotations

import shutil
import tempfile
from pathlib import Path

from fastapi import UploadFile

from .errors import file_error

MAX_UPLOAD_BYTES = 10 * 1024 * 1024
SUPPORTED_FORMATS = {".xls": "xls", ".xlsx": "xlsx"}


class TaskWorkspace:
    def __init__(self, root: Path):
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)

    def create(self) -> Path:
        return Path(tempfile.mkdtemp(prefix="task-", dir=self.root))

    @staticmethod
    def remove(path: Path) -> None:
        shutil.rmtree(path, ignore_errors=True)

    def cleanup_stale(self, max_age_seconds: int = 600) -> None:
        import time

        cutoff = time.time() - max_age_seconds
        for child in self.root.iterdir():
            try:
                if child.is_dir() and child.stat().st_mtime < cutoff:
                    shutil.rmtree(child, ignore_errors=True)
            except OSError:
                continue


async def save_upload(upload: UploadFile, destination: Path) -> tuple[Path, str, int]:
    filename = upload.filename or ""
    suffix = Path(filename).suffix.lower()
    file_format = SUPPORTED_FORMATS.get(suffix)
    if file_format is None:
        raise file_error("FILE-001", "仅支持 .xls 和 .xlsx 文件。")

    size = 0
    try:
        with destination.open("wb") as stream:
            while True:
                chunk = await upload.read(1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > MAX_UPLOAD_BYTES:
                    raise file_error("FILE-002", "单个文件不能超过 10 MB。")
                stream.write(chunk)
    except Exception:
        destination.unlink(missing_ok=True)
        raise
    finally:
        await upload.close()
    return destination, file_format, size

