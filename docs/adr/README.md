# 架构决策记录

本目录保存产品和技术设计中的关键决策。只有在讨论确认后才新增 ADR，避免把建议误记为既定需求。

每份 ADR 包含：背景、决策、备选方案、影响和状态。

## 已记录决策

- [ADR-0001：以 `platform.xlsx` 兼容结构作为输入契约](0001-template-contract.md)
- [ADR-0002：写入范围严格限制为最新时间段的 A 至 D 列](0002-write-boundary.md)
- [ADR-0003：动态替换最新时间段的股票代码数据](0003-dynamic-replacement.md)
- [ADR-0004：按严格时间顺序识别最新时间段](0004-latest-period-validation.md)
- [ADR-0005：价格查询严格使用指定日期](0005-exact-quote-date.md)
- [ADR-0006：使用不复权行情价格](0006-unadjusted-prices.md)
- [ADR-0007：仅支持三家交易所当前上市 A 股](0007-supported-securities.md)
- [ADR-0008：可选字段采用独立复选和保留原值语义](0008-optional-fields.md)
- [ADR-0009：最新时间段使用末尾动态数据区](0009-dynamic-region-boundary.md)
- [ADR-0010：输出文件保持源工作簿格式](0010-preserve-workbook-format.md)
- [ADR-0011：使用统一网页/PWA 下载交付](0011-pwa-delivery.md)
- [ADR-0012：通过结构唯一识别目标工作表](0012-target-worksheet.md)
- [ADR-0013：采用严格 Excel 功能兼容范围](0013-strict-excel-compatibility.md)
- [ADR-0014：产品定位为个人低频工具](0014-personal-low-frequency-use.md)
- [ADR-0015：首版使用 AKShare 东方财富行情](0015-akshare-market-data.md)
- [ADR-0016：Excel 文件由后端临时处理且不留存](0016-temporary-file-processing.md)
- [ADR-0017：使用单用户密码保护公网工具](0017-single-user-authentication.md)
- [ADR-0018：采用个人低频任务运行限制](0018-operational-limits.md)
- [ADR-0019：自检不计算受保护公式结果](0019-formula-recalculation-boundary.md)
- [ADR-0020：新增股票行只复制 A 至 D 单元格样式](0020-dynamic-cell-formatting.md)
- [ADR-0021：使用结构化错误代码且不生成部分文件](0021-error-reporting.md)
- [ADR-0022：日期统一使用严格点分格式](0022-date-input-format.md)
- [ADR-0023：使用单页响应式界面和两次短暂上传](0023-page-and-upload-flow.md)
- [ADR-0024：采用 FastAPI 与 Apache POI 单镜像架构](0024-technical-architecture.md)
- [ADR-0025：冻结 MVP 范围并以安全验收项作为发布门槛](0025-mvp-scope-and-release-gate.md)
