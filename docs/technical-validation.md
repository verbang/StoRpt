# Excel 技术验证

> 状态：进行中  
> 阶段：MVP 第 1 阶段  
> 更新日期：2026-07-23

## 目标

在不要求本机安装 JDK、Maven 或 Docker 的前提下，通过远程 CI 验证
Apache POI 对兼容 `.xls` 和 `.xlsx` 工作簿的读取、保存、重开及受控写入能力。

技术验证不得降低产品设计中的安全边界。任何 E:S、历史区块、非目标工作表、
公式、样式或合并关系的非授权变化都是验证失败，而不是可以接受的实现差异。

## 当前自动化验证

- 使用 Java 21 和 Apache POI 同时加载 HSSF `.xls` 与 XSSF `.xlsx`。
- 对工作簿建立语义快照，覆盖工作表元数据、行列属性、单元格值、公式、样式、
  批注、超链接和合并区域。
- 对 `platform.xlsx` 执行无业务修改的保存、重开和全工作簿快照比较。
- 对 `platform.xlsx` 执行最新时间段允许单元格写入，并比较全部受保护内容。
- 对程序化 HSSF 样本执行 `.xls` 保存、重开和快照比较。

## 阶段退出条件

- [x] GitHub Actions 中全部现有验证通过（2026-07-23，第 5 次运行）。
- [x] 增加一份由 Excel 或 WPS 实际保存的兼容 `.xls` 样本并通过验证（`platform2.xls`，2026-07-26）。
- [x] 为密码、保护、签名、外链、透视表、图表、图片、形状和嵌入对象建立可获得的拒绝样本及探测结果（2026-07-26）。VBA 宏和静态数据连接按 [ADR-0013](adr/0013-strict-excel-compatibility.md) 修订予以容忍，不再纳入拒绝探测。
- [x] 验证新增 A:D 单元格的样式复制不改变 E:S 或整行属性（`WorkbookWriterTest.styleCopyLeavesExistingRowsAndProtectedColumnsUntouched`，Excel 技术验证 CI 通过，2026-07-30）。
- [x] 验证代码缩减时只清除 A 列尾部旧代码，未勾选 B:D 保持原坐标值（`WorkbookWriterTest.shorterCodeListClearsOldCodeTailAndPreservesOptionalColumns`，2026-07-26）。
- [x] 记录 Apache POI 对 `.xls` 与 `.xlsx` 的已知差异和最终 Go/No-Go 结论（[ADR-0026](adr/0026-poi-hssf-xssf-known-differences.md)，2026-07-30，Go，附两条 No-Go 触发条件）。

## 发布测试矩阵覆盖（2026-07-26）

AC 第 7 节的自动化矩阵已落地，逐项映射见 [`acceptance-criteria.md`](acceptance-criteria.md) 第 7.1 节。覆盖：

- 单元格级（Java Worker）：8 种复选框组合、代码缩减、动态扩展、结构化差异自检。
- 编排级（后端）：行情失败原子性（MARKET-002）、非 A 股代码（MARKET-001）、并发拒绝（SYSTEM-002）、180 秒超时（SYSTEM-001）、输出格式保持。

未纳入自动化的两项（属部署联调阶段）：跨浏览器关键流程（iOS Safari/Android/HarmonyOS）、真实 AKShare 实时冒烟。

## 已知限制

程序化生成的 `.xls` 只能证明基础 HSSF 往返能力，不能替代真实 Excel/WPS 文件。
`platform2.xls` 已覆盖真实 `.xls` 的模板识别与兼容样本往返，但 `.xls` 路径的
不兼容功能拒绝（图表、图片、形状、嵌入对象等）仍依赖 Escher/drawing 粗判合并，
仍需更多真实样本逐项细化。`.xls` 的数字签名探测已实现：通过
`HSSFWorkbook.getDirectory()` 枚举 OLE2 根存储的签名流并拒绝
（`UnsupportedFeatureDetector.detectHssf`），匹配 MS-OFFCRYPTO 定义的二进制签名容器
（`_signatures` / `_xmlsignatures`），并保留 `\u0005DigitalSignature` 作为遗留流名兜底。
合成测试用 `POIFSFileSystem(InputStream)` 向干净工作簿注入 `_signatures` 根流、写盘后
先用 `hasEntry` 自检流确实落盘、再交探测器断言 `TEMPLATE-005`
（`UnsupportedFeatureDetectorTest.rejectsHssfDigitalSignature`）。真实签名样本产生的
精确流名仍待部署联调阶段用真实样本确认；若与假设不符，将作为 ADR-0026 的 No-Go
触发条件处理。
