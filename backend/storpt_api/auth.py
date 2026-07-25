from __future__ import annotations

import base64
import binascii
import getpass
import hashlib
import hmac
import json
import os
import secrets
import threading
import time
from dataclasses import dataclass, field

from .errors import auth_error, system_error


SESSION_COOKIE = "storpt_session"
SESSION_SECONDS = 7 * 24 * 60 * 60
_SCRYPT_N = 2**14
_SCRYPT_R = 8
_SCRYPT_P = 1
_HASH_BYTES = 32


def _encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def hash_password(password: str, salt: bytes | None = None) -> str:
    if not password:
        raise ValueError("password must not be empty")
    actual_salt = salt or secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode("utf-8"),
        salt=actual_salt,
        n=_SCRYPT_N,
        r=_SCRYPT_R,
        p=_SCRYPT_P,
        dklen=_HASH_BYTES,
    )
    return f"scrypt${_SCRYPT_N}${_SCRYPT_R}${_SCRYPT_P}${_encode(actual_salt)}${_encode(digest)}"


def verify_password(password: str, encoded: str) -> bool:
    try:
        algorithm, n, r, p, salt, expected = encoded.split("$", 5)
        if algorithm != "scrypt":
            return False
        digest = hashlib.scrypt(
            password.encode("utf-8"),
            salt=_decode(salt),
            n=int(n),
            r=int(r),
            p=int(p),
            dklen=len(_decode(expected)),
        )
        return hmac.compare_digest(digest, _decode(expected))
    except (ValueError, TypeError, binascii.Error):
        return False


@dataclass(slots=True)
class AuthManager:
    password_hash: str | None
    signing_key: bytes | None
    session_seconds: int = SESSION_SECONDS
    failure_limit: int = 5
    lock_seconds: int = 15 * 60
    _failures: int = 0
    _locked_until: float = 0.0
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    @classmethod
    def from_environment(cls) -> "AuthManager":
        password_hash = os.getenv("STORPT_PASSWORD_HASH")
        signing_secret = os.getenv("STORPT_SESSION_SECRET")
        return cls(password_hash, signing_secret.encode("utf-8") if signing_secret else None)

    def authenticate(self, password: str, now: float | None = None) -> str:
        timestamp = time.time() if now is None else now
        self._require_configuration()
        with self._lock:
            if timestamp < self._locked_until:
                retry_after = max(1, int(self._locked_until - timestamp))
                raise auth_error(
                    "AUTH-003",
                    "登录尝试已暂时锁定。",
                    429,
                    {"retryAfter": retry_after},
                )
            if not verify_password(password, self.password_hash or ""):
                self._failures += 1
                if self._failures >= self.failure_limit:
                    self._locked_until = timestamp + self.lock_seconds
                    self._failures = 0
                    raise auth_error(
                        "AUTH-003",
                        "登录失败次数过多，请稍后再试。",
                        429,
                        {"retryAfter": self.lock_seconds},
                    )
                raise auth_error("AUTH-002", "访问密码不正确。", 401)
            self._failures = 0
            self._locked_until = 0.0
        return self._issue_session(timestamp)

    def require_session(self, token: str | None, now: float | None = None) -> None:
        if not token:
            raise auth_error("AUTH-001", "请先登录。", 401)
        self._require_configuration()
        try:
            payload_text, signature_text = token.split(".", 1)
            expected = hmac.new(
                self.signing_key or b"",
                payload_text.encode("ascii"),
                hashlib.sha256,
            ).digest()
            if not hmac.compare_digest(expected, _decode(signature_text)):
                raise ValueError
            payload = json.loads(_decode(payload_text))
            timestamp = time.time() if now is None else now
            if payload.get("v") != 1 or not isinstance(payload.get("exp"), int):
                raise ValueError
            if timestamp >= payload["exp"]:
                raise ValueError
        except (ValueError, TypeError, UnicodeError, binascii.Error, json.JSONDecodeError):
            raise auth_error("AUTH-001", "登录会话无效或已过期。", 401) from None

    def _issue_session(self, timestamp: float) -> str:
        payload = json.dumps(
            {
                "v": 1,
                "iat": int(timestamp),
                "exp": int(timestamp) + self.session_seconds,
                "nonce": secrets.token_urlsafe(12),
            },
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        payload_text = _encode(payload)
        signature = hmac.new(
            self.signing_key or b"",
            payload_text.encode("ascii"),
            hashlib.sha256,
        ).digest()
        return f"{payload_text}.{_encode(signature)}"

    def _require_configuration(self) -> None:
        if not self.password_hash or not self.signing_key:
            raise system_error("SYSTEM-003", "认证环境变量尚未配置。", 503)


if __name__ == "__main__":
    print(hash_password(getpass.getpass("Access password: ")))
