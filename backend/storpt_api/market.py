from __future__ import annotations

import re
import threading
import time
from datetime import date
from decimal import Decimal
from typing import Callable, Protocol

from .errors import BackendError, input_error
from .models import MarketRow

CODE_PATTERN = re.compile(r"^[0-9]{6}$")
SEPARATORS = re.compile(r"[\s,，;；]+")


def parse_codes(raw: str) -> list[str]:
    codes = [item for item in SEPARATORS.split(raw.strip()) if item]
    if not codes:
        raise input_error("至少需要输入一只股票代码。")
    invalid = [code for code in codes if not CODE_PATTERN.fullmatch(code)]
    if invalid:
        raise input_error("股票代码必须是六位纯数字：" + ", ".join(invalid))
    duplicates = sorted({code for code in codes if codes.count(code) > 1})
    if duplicates:
        raise input_error("股票代码不能重复：" + ", ".join(duplicates))
    return codes


class MarketDataProvider(Protocol):
    def fetch(
        self,
        codes: list[str],
        start_date: date,
        end_date: date,
        fill_name: bool,
        fill_ideal_buy: bool,
        fill_ideal_sell: bool,
        progress: Callable[[int, int], None] | None = None,
    ) -> list[MarketRow]: ...


class AkshareMarketDataProvider:
    """Small adapter isolating AKShare field names from the API and Excel layers."""

    def __init__(self) -> None:
        self._listing_date: date | None = None
        self._listing_names: dict[str, str] = {}
        self._listing_lock = threading.Lock()

    @staticmethod
    def _retry(call, message: str):
        last_error: Exception | None = None
        for attempt in range(4):
            try:
                return call()
            except Exception as exc:  # AKShare exposes several network exception types.
                last_error = exc
                if attempt < 3:
                    time.sleep(0.25 * (2**attempt))
        raise BackendError("MARKET-001", "MARKET", "market", "行情服务不可用", message, 503) from last_error

    def _listing(self, ak) -> dict[str, str]:
        today = date.today()
        with self._listing_lock:
            if self._listing_date == today:
                return dict(self._listing_names)
            listing = self._retry(ak.stock_info_a_code_name, "无法读取当前上市 A 股清单。")
            names: dict[str, str] = {}
            for _, row in listing.iterrows():
                code = str(row.get("code", row.get("证券代码", ""))).zfill(6)
                name = row.get("name", row.get("证券简称"))
                if code and name is not None:
                    names[code] = str(name)
            self._listing_date = today
            self._listing_names = names
            return dict(names)

    def fetch(
        self,
        codes: list[str],
        start_date: date,
        end_date: date,
        fill_name: bool,
        fill_ideal_buy: bool,
        fill_ideal_sell: bool,
        progress: Callable[[int, int], None] | None = None,
    ) -> list[MarketRow]:
        try:
            import akshare as ak
        except ImportError as exc:
            raise BackendError("MARKET-001", "MARKET", "market", "行情服务不可用", "AKShare 未安装。", 503) from exc

        names = self._listing(ak)

        result: list[MarketRow] = []
        for index, code in enumerate(codes, start=1):
            if code not in names:
                raise BackendError("MARKET-001", "MARKET", "market", "股票清单校验失败", f"股票代码不是当前上市沪深京 A 股：{code}")
            buy: Decimal | None = None
            sell: Decimal | None = None
            if fill_ideal_buy or fill_ideal_sell:
                history = self._retry(
                    lambda: ak.stock_zh_a_hist(
                        symbol=code,
                        period="daily",
                        start_date=start_date.strftime("%Y%m%d"),
                        end_date=end_date.strftime("%Y%m%d"),
                        adjust="",
                    ),
                    f"读取股票 {code} 行情失败。",
                )
                if history is None or len(history) == 0:
                    raise BackendError("MARKET-002", "MARKET", "market", "指定日期无行情", f"股票 {code} 没有指定日期行情。")
                date_column = "日期" if "日期" in history.columns else "date"
                matching = history[history[date_column].astype(str).str[:10] == start_date.isoformat()]
                if fill_ideal_sell:
                    matching_end = history[history[date_column].astype(str).str[:10] == end_date.isoformat()]
                else:
                    matching_end = matching
                if len(matching) == 0 or len(matching_end) == 0:
                    raise BackendError("MARKET-002", "MARKET", "market", "指定日期无行情", f"股票 {code} 没有指定日期行情。")
                if fill_ideal_buy:
                    buy = Decimal(str(matching.iloc[0]["开盘"]))
                if fill_ideal_sell:
                    sell = Decimal(str(matching_end.iloc[0]["收盘"]))
            result.append(MarketRow(code, names.get(code), buy, sell))
            if progress is not None:
                progress(index, len(codes))
        return result
