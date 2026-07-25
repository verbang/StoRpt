from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Any, Literal


@dataclass(frozen=True, slots=True)
class MarketRow:
    code: str
    name: str | None
    ideal_buy: Decimal | None
    ideal_sell: Decimal | None

    def as_worker_row(self) -> dict[str, Any]:
        row: dict[str, Any] = {"code": self.code}
        if self.name is not None:
            row["name"] = self.name
        if self.ideal_buy is not None:
            row["idealBuy"] = float(self.ideal_buy)
        if self.ideal_sell is not None:
            row["idealSell"] = float(self.ideal_sell)
        return row


@dataclass(frozen=True, slots=True)
class WorkerResult:
    operation: Literal["analyze", "write"]
    metadata: dict[str, Any]
    output_path: Path | None = None


@dataclass(frozen=True, slots=True)
class ProcessInput:
    sheet_index: int
    title_row: int
    data_start_row: int
    start_date: str
    end_date: str
    codes: list[str]
    fill_name: bool
    fill_ideal_buy: bool
    fill_ideal_sell: bool

