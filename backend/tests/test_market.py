from __future__ import annotations

import pytest

from storpt_api.errors import BackendError
from storpt_api.market import AkshareMarketDataProvider, parse_codes


def test_parse_codes_supports_product_separators_and_preserves_order():
    assert parse_codes("000001，600000\n300750; 688001") == ["000001", "600000", "300750", "688001"]


def test_parse_codes_rejects_invalid_and_duplicate_values():
    with pytest.raises(BackendError) as invalid:
        parse_codes("000001 ABC123")
    assert invalid.value.code == "INPUT-001"

    with pytest.raises(BackendError) as duplicate:
        parse_codes("000001,000001")
    assert duplicate.value.code == "INPUT-001"


def test_market_retry_allows_four_total_attempts(monkeypatch):
    provider = AkshareMarketDataProvider()
    attempts = 0

    def flaky():
        nonlocal attempts
        attempts += 1
        if attempts < 4:
            raise OSError("temporary")
        return "ok"

    monkeypatch.setattr("storpt_api.market.time.sleep", lambda _: None)
    assert provider._retry(flaky, "failed") == "ok"
    assert attempts == 4


def test_market_retry_reports_after_four_failures(monkeypatch):
    provider = AkshareMarketDataProvider()
    monkeypatch.setattr("storpt_api.market.time.sleep", lambda _: None)

    with pytest.raises(BackendError) as failure:
        provider._retry(lambda: (_ for _ in ()).throw(OSError("down")), "failed")
    assert failure.value.code == "MARKET-001"
