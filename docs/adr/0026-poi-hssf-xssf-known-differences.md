# ADR-0026：记录 Apache POI 对 `.xls` 与 `.xlsx` 的已知差异并给出 Go/No-Go 结论

- 状态：已接受
- 日期：2026-07-30

## 背景

StoRpt 必须在不进行格式互转的前提下，分别可靠支持 HSSF `.xls` 与 XSSF `.xlsx`
（见 [ADR-0010](0010-preserve-workbook-format.md)）。两种格式在 Apache POI 5.4.1
中的表现并非完全对称：同一安全规则在两种格式下的探测路径、粒度和稳定性都不同。
这些差异此前散落在 [ADR-0010](0010-preserve-workbook-format.md)、
[ADR-0013](0013-strict-excel-compatibility.md)、[ADR-0024](0024-technical-architecture.md)、
源码注释和提交历史里，没有统一记录，导致「Apache POI 对两种格式的保真能力」这一技术验证
退出条件无法给出明确结论。本 ADR 汇总已实证的差异并给出最终 Go/No-Go。

## 决策

记录以下十项已核实的 HSSF/XSSF 差异，作为写入与保真边界的既定约束：

- **行容量**：`.xls` 上限 65,536 行，`.xlsx` 上限 1,048,576 行（客观容量上限）。
- **计算模式记录访问**：HSSF 经内部记录 `CalcModeRecord`（`getInternalWorkbook().findFirstRecordBySid`）；
  XSSF 经 `getCTWorkbook().getCalcPr()`。两者语义等价，但 API 不同。
- **数字签名探测**：XSSF 用 OPC 关系 `DIGITAL_SIGNATURE_ORIGIN`；HSSF 用 OLE2 根存储的
  签名流（`\u0005DigitalSignature` / `DigitalSignature` / `_xmlsignatures`），经
  `HSSFWorkbook.getDirectory()` 枚举（2026-07-30 补齐，见 `UnsupportedFeatureDetector.detectHssf`）。
- **绘图/图表/图片/形状/对象探测粒度**：XSSF 可逐类型区分（图表、图片、形状、嵌入对象）；
  HSSF 仅能用 `getDrawingEscherAggregate() != null` 粗判，把上述四类合并为一条拒绝信息。
- **需写盘重开才可探测**：drawing、picture、external-links 在内存工作簿上不可见，必须
  序列化后重开才能被探测到（见 `UnsupportedFeatureDetectorTest` 类注释）。
- **外链 API 差异**：XSSF `getExternalLinksTable()`；HSSF `SupBookRecord.isExternalReferences()`。
  程序化构造的外链跨 save/reopen 持久化不可靠（提交 `40f83d9` 据此移除了对应测试）。
- **XSSF 图片/形状 instanceof 顺序敏感**：`XSSFPicture` 继承自 `XSSFShape`，必须先判断图片
  再落到通用形状分支，否则图片会被报成「形状」（提交 `7f114e0`）。
- **POI 5.x API 稳定性**：部分接口（如 `PackageRelationshipCollection`）跨小版本可用性不保证，
  对 OPC/关系类探测须以 `try/catch (RuntimeException)` 包裹并容错（提交 `40f83d9`）。
- **公式缓存值**：两种格式均不校验 E:S 的公式缓存业务结果，只验证公式文本、格式、合并关系
  与计算模式（见 [ADR-0019](0019-formula-recalculation-boundary.md)），由 Excel/WPS 打开时重算。
- **测试覆盖不对称**：写入器与不兼容功能探测器的自动化测试以 XSSF 为主；HSSF 路径由程序化
  往返、真实样本 `platform2.xls`、计算模式测试与（本次新增的）`.xls` 签名探测覆盖。

## 影响

- **Go 结论**：基于 `.xls` 真实样本 `platform2.xls` 的模板识别与往返已在 CI 通过、HSSF 受控
  写入与保真快照在 CI 通过，且 `.xls` 数字签名探测已补齐，Apache POI 5.4.1 对两种格式的
  保真能力**满足** [ADR-0025](0025-mvp-scope-and-release-gate.md) 的写入边界要求。
- **No-Go 触发条件**（任一发生则降级为 No-Go 并扩大 HSSF 拒绝范围或调整方案）：
  1. 取得真实签名 `.xls` 样本后，发现其签名流名不在当前匹配清单内，导致探测漏判；
  2. 真实 Excel/WPS 保存的 `.xls` 出现 `getDrawingEscherAggregate()` 无法覆盖的不兼容内容，
  或粗判合并把兼容内容误判为不兼容。
- `.xls` 不兼容功能的**细化**（区分图表/图片/形状/对象各自拒绝信息）留作已知限制，不阻断
  MVP 发布：合并拒绝信息已足够让用户知道「工作表包含图表、图片、形状或嵌入对象」。
- 本 ADR 不变更任何代码行为，仅记录既定事实与结论；后续若差异项发生变化（如新增 `poi-scratchpad`
  依赖、升级 POI 主版本），须在此 ADR 追加修订。
