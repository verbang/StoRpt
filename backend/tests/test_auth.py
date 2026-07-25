from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from storpt_api.auth import AuthManager, SESSION_COOKIE, hash_password, verify_password
from storpt_api.errors import BackendError
from storpt_api.main import create_app

from .test_api import StubMarket, StubWorker
from storpt_api.service import TaskService
from storpt_api.storage import TaskWorkspace


PASSWORD = "a long test access password"
PASSWORD_HASH = hash_password(PASSWORD, b"fixed-test-salt")


def test_scrypt_password_hash_round_trip():
    assert verify_password(PASSWORD, PASSWORD_HASH)
    assert not verify_password("wrong password", PASSWORD_HASH)
    assert not verify_password(PASSWORD, "not-a-valid-hash")


def test_signed_session_rejects_tampering_and_expiration():
    auth = AuthManager(PASSWORD_HASH, b"session-signing-key", session_seconds=60)
    token = auth.authenticate(PASSWORD, now=1000)

    auth.require_session(token, now=1059)
    with pytest.raises(BackendError) as expired:
        auth.require_session(token, now=1060)
    assert expired.value.code == "AUTH-001"
    with pytest.raises(BackendError) as tampered:
        auth.require_session(token + "x", now=1001)
    assert tampered.value.code == "AUTH-001"


def test_login_lockout_has_retry_window():
    auth = AuthManager(
        PASSWORD_HASH,
        b"session-signing-key",
        failure_limit=2,
        lock_seconds=30,
    )

    with pytest.raises(BackendError) as first:
        auth.authenticate("wrong", now=1000)
    assert first.value.code == "AUTH-002"
    with pytest.raises(BackendError) as second:
        auth.authenticate("wrong", now=1001)
    assert second.value.code == "AUTH-003"
    assert second.value.details == {"retryAfter": 30}
    with pytest.raises(BackendError) as locked:
        auth.authenticate(PASSWORD, now=1010)
    assert locked.value.code == "AUTH-003"
    assert auth.authenticate(PASSWORD, now=1032)


def test_login_sets_secure_cookie_and_logout_clears_it(tmp_path):
    service = TaskService(TaskWorkspace(tmp_path / "tasks"), StubWorker(), StubMarket())
    auth = AuthManager(PASSWORD_HASH, b"session-signing-key")
    client = TestClient(create_app(service, auth), base_url="https://testserver")

    response = client.post("/api/auth/login", json={"password": PASSWORD})

    assert response.status_code == 200
    cookie = response.headers["set-cookie"]
    assert f"{SESSION_COOKIE}=" in cookie
    assert "HttpOnly" in cookie
    assert "Secure" in cookie
    assert "SameSite=strict" in cookie
    assert client.get("/api/auth/session").status_code == 200

    logout = client.post("/api/auth/logout")
    assert logout.status_code == 200
    assert client.get("/api/auth/session").status_code == 401
