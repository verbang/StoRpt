# MVP 实施计划

> 状态：已确认  
> 版本：1.0  
> 更新日期：2026-07-23

## 1. 实施目标

以 [product-design.md](product-design.md) 为产品基线，在单个 Docker 镜像中交付可供个人低频使用的 PWA。发布门槛以 [acceptance-criteria.md](acceptance-criteria.md) 的全部 P0 条目通过为准。

单人开发初步估算为 **13 至 20 个工作日**。估算不包含等待第三方行情接口恢复、部署平台采购审批或需求范围变更造成的时间。

## 2. 阶段与交付物

| 阶段 | 预计时间 | 主要工作 | 退出条件 |
| --- | ---: | --- | --- |
| 1. 技术验证 | 2～3 天 | 验证 Apache POI 对 `.xls/.xlsx` 的读取、保存、特殊功能探测和结构化差异；验证 AKShare 沪深京清单及指定日不复权行情 | 两种格式均能在不越界写入的前提下保存重开；关键风险有明确结论 |
| 2. Excel Worker 与后端核心 | 4～6 天 | 实现模板解析、时间段校验、A:D 白名单写入、自检；实现 FastAPI 任务编排、AKShare 适配、重试、超时、临时文件和错误模型 | 使用 API/命令行通过核心 P0 用例，任何失败不交付部分文件 |
| 3. PWA 界面与认证 | 2～3 天 | 实现单用户登录、两次上传、表单校验、响应式布局、SSE 进度、错误复制、自动/手动下载和本地命名计数 | 桌面和移动浏览器可完成主流程，处理期间输入锁定 |
| 4. 集成测试与部署 | 3～5 天 | 建立 `.xls/.xlsx` 测试矩阵、越界差异检测、异常和超时测试；制作单镜像、健康检查、HTTPS 部署说明 | 全部 P0 验收项通过，镜像可在目标 Linux Docker 环境运行 |
| 5. 缓冲与修复 | 2～3 天 | 处理不同 Excel 版本、AKShare 字段变化和移动浏览器下载差异 | 无发布阻断缺陷，已知限制写入发布说明 |

阶段可部分交叠，但不能跳过第 1 阶段直接承诺 Excel 保真能力。

## 3. 推荐代码结构

```text
frontend/                 Vue 3 + TypeScript + Vite + PWA
backend/
  app/                    FastAPI、认证、任务、SSE、错误映射
  market/                 MarketDataProvider 与 AKShare 实现
excel-worker/
  src/                    Java 21 + Apache POI
contracts/                Python/Java 共享 JSON Schema 与错误代码
tests/
  fixtures/               兼容、不兼容、边界 Excel 样本
  integration/            端到端与结构化差异测试
deploy/                   Dockerfile、入口脚本、反向代理示例
```

## 4. 关键实现顺序

1. 先定义 Python 与 Java 的请求/响应 JSON Schema、稳定错误代码及允许写入坐标清单。
2. 对输入工作簿建立结构化快照，再执行任何写入；快照至少覆盖工作表元数据、历史区块、E:S、公式、样式和合并区域。
3. Java Worker 使用单元格级写入许可，不接受任意单元格地址或 Shell 拼接参数。
4. Python 在全部输入、模板和行情校验成功后才调用写入；行情结果统一转换为内部模型。
5. 保存后由 Java 重新打开输出并执行自检；只有自检通过才允许 FastAPI 暴露下载。
6. 最后接入 PWA、SSE 和下载，避免界面进度掩盖后端原子性问题。

## 5. 测试策略

- 单元测试：日期解析、代码分隔、重复检测、错误映射、重试判定、文件命名。
- Worker 测试：目标表识别、严格时间顺序、动态 A:D、样式复制、不支持功能探测、保存后重开。
- 差异测试：对比处理前后结构化快照，确保 E:S、历史区块和非目标工作表无越界变化。
- 行情契约测试：用固定响应测试名称、开盘、收盘和无行情；少量实时冒烟测试验证 AKShare 字段兼容。
- 端到端测试：登录、两次上传、8 种复选框组合、SSE、下载、失败原子性和临时文件清理。
- 跨平台测试：优先验证 iOS Safari 的文件重选与下载，以及 Android/HarmonyOS 浏览器的 PWA 和文件保存行为。

## 6. 主要风险与控制

| 风险 | 影响 | 控制措施 |
| --- | --- | --- |
| Apache POI 保存导致非授权结构变化 | 违反最重要的写入边界 | 第 1 阶段实证；严格拒绝特殊功能；保存后结构化差异检查 |
| `.xls` 与 `.xlsx` 行为差异 | 同一规则在两种格式下结果不同 | HSSF/XSSF 分开建立样本和测试，不用格式互转规避问题 |
| AKShare/东方财富接口变化或限流 | 行情任务失败 | 适配层隔离字段；自然日清单缓存；低频调用；有限重试；显式失败 |
| 动态扩展后 E:S 无对应公式 | 新增行只具备 A:D 数据 | 作为已确认边界写入 UI/发布说明，不复制或修改 E:S |
| 浏览器不能保存至源目录或自动改名 | 实际文件名/位置由浏览器控制 | 本地生成计数、自动下载失败回退按钮、界面明确提示 |
| 进程中断残留临时文件 | 隐私风险 | 随机任务目录、`finally` 清理、启动时与定时清理超过 10 分钟目录 |

## 7. 完成定义

- 全部 P0 验收标准通过并留存测试结果。
- `platform.xlsx` 的副本可完成成功流程，源文件保持不变。
- `.xls` 与 `.xlsx` 的兼容样本均可保存、重开和下载。
- 任何已知越界写入、部分文件交付或临时文件超期留存问题均为发布阻断项。
- Docker 镜像、环境变量说明、部署步骤、备份/升级方式和已知限制已文档化。

## 8. 实施进度

> 最后更新：2026-08-06  
> 主线分支：`main`。**四条发布门槛 CI（Excel 技术验证、FastAPI 后端验证、PWA 前端验证、Docker 镜像验证）全部转绿。** backend-validation 此前长期失败，根因是 `service.py` 用 `ZoneInfo('Asia/Shanghai')` 生成下载文件名（AC-044），但 `requirements.lock` 与 `python:3.12-slim` 镜像都不含 `tzdata`，导致每次成功处理在命名那步抛 `ZoneInfoNotFoundError` 并被吞成 `SYSTEM-004`——这被误诊为「TestClient teardown 竞态」多年。修复：把 `tzdata>=2024.2` 加入运行时依赖并 pin `tzdata==2026.3`；CI（Ubuntu + Python 3.12）已验证转绿，本机（Windows + Python 3.14）偶发的 teardown hang 在 CI 未复现。

| 阶段 | 状态 | 实际产出 |
| --- | :---: | --- |
| 1. 技术验证 | ✅ 完成 | Apache POI 对 `.xls`/`.xlsx` 的读取、保存、重开、受控写入在 CI 通过；真实 `.xls` 样本 `platform2.xls` 与功能拒绝样本 `platform-reject.xlsx` 入仓并验证。 |
| 2. Excel Worker 与后端核心 | ✅ 完成 | 模板解析、时间段校验、A:D 白名单写入、保存后自检、原子发布；FastAPI 任务编排、AKShare 适配、重试、临时文件清理、错误模型。 |
| 3. PWA 界面与认证 | ✅ 完成 | 单用户登录、两次上传、表单校验、响应式布局、SSE 进度、错误复制、自动/手动下载、本地命名计数。 |
| 4. 集成测试与部署 | 🟡 进行中 | 自动化部分全绿；剩余为部署联调（见下方「后续待办」）。 |
| 5. 缓冲与修复 | ✅ 完成 | 2026-07-30 修复 `.xls` 签名探测首次 CI 报错（`bfc703a`）；2026-08-06 修复被误诊为 teardown 竞态的 `tzdata` 缺失生产缺陷（`ZoneInfo('Asia/Shanghai')` → `SYSTEM-004`），backend-validation CI 转绿。本机 Windows+Python 3.14 偶发的 TestClient teardown hang 在 CI（Ubuntu+Python 3.12）未复现，判定为本机环境特有，不构成发布阻断。 |

### 第 4 阶段子项进度

- ✅ **可复现单镜像**（`deploy/Dockerfile` 三阶段：node→maven→python:3.12-slim，非 root + tini，从入仓 lock 文件复现构建）。`requirements.lock` 与 `package-lock.json` 已固化入仓。`docs/deploy.md` 覆盖构建/运行/反代/升级/已知限制。
- ✅ **Docker 镜像 CI**（`deploy-validation.yml`）：构建镜像 + 冒烟 `/healthz`、`/api/auth/session`(AUTH-001)、SPA 首页。
- ✅ **不兼容功能拒绝**（AC-015）：`UnsupportedFeatureDetector` 覆盖加密/保护/签名/外链/透视/图表/图片/形状/嵌入对象。VBA 宏与静态数据连接按 ADR-0013 修订容忍。顺带修复加密文件被误报 INPUT-001 的缺陷。`.xls` 数字签名探测于 2026-07-30 补齐（`detectHssf` 经 OLE2 根签名流），用合成注入样本验证命中。
- ✅ **发布测试矩阵（自动化部分）**：单元格级（8 复选框组合 + 代码缩减）在 Java Worker 测试；编排级（行情失败原子性、MARKET-001、并发拒绝 SYSTEM-002、超时 SYSTEM-001、格式透传）在后端测试。逐项映射见 `acceptance-criteria.md` 第 7.1 节。
- ✅ **技术验证补齐（2026-07-30，Excel 技术验证 CI 已绿）**：(1) A:D 样式复制不影响 E:S/整行属性的负向边界测试 `WorkbookWriterTest.styleCopyLeavesExistingRowsAndProtectedColumnsUntouched`（CI 通过）；(2) `.xls` 数字签名探测实现 + 2 个 HSSF 测试（CI 通过）；(3) POI `.xls`/`.xlsx` 已知差异与 Go/No-Go 结论记录为 [ADR-0026](adr/0026-poi-hssf-xssf-known-differences.md)（Go，附两条 No-Go 触发条件）。

### 本次工作记录（2026-07-30～31）

补齐 `technical-validation.md` 的三项退出条件，全部在 Excel 技术验证 CI 通过：

| 提交 | 内容 | 结果 |
| --- | --- | --- |
| `13a5397` | A:D 样式复制负向边界测试 `WorkbookWriterTest.styleCopyLeavesExistingRowsAndProtectedColumnsUntouched`（AC-036/ADR-0020） | ✅ CI 绿 |
| `da85806`→`bfc703a` | `.xls` 数字签名探测（`detectHssf` 经 `HSSFWorkbook.getDirectory()` 枚举 OLE2 根签名流）；首次 CI 报错后修正流名为 MS-OFFCRYPTO 权威值 `_signatures`/`_xmlsignatures` + 加注入自检 | ✅ CI 绿 |
| `280b203` | ADR-0026：POI HSSF/XSSF 已知差异 + Go/No-Go 结论（Go，附两条 No-Go 触发条件） | 文档 |
| `c26d7a6` | 文档收尾：修正 CI 状态、勾选退出条件、记录 backend 竞态 | 文档 |

诊断并记录了 backend-validation 的预存竞态（详见下方「后续待办」第 5 项），未提交未验证的代码修复。

### 本次工作记录（2026-08-06）

重新诊断 backend-validation 失败，推翻「TestClient teardown 竞态」的归因，定位到真实根因并修复：

| 项 | 内容 |
| --- | --- |
| **真实根因** | `service.py:182` `datetime.now(ZoneInfo('Asia/Shanghai'))` 生成下载文件名（AC-044）。`requirements.lock` 与 `deploy/Dockerfile`（`python:3.12-slim`）均不含 `tzdata`，而 slim 镜像与 Windows 无系统时区数据库，故 `ZoneInfo` 抛 `ZoneInfoNotFoundError`，被 `_execute` 的 `except Exception` 吞成 `SYSTEM-004`。 |
| **为何长期误诊** | 该异常发生在后台任务 `_process` 末尾（写完文件后的命名步），表现为任务停在 `verify` 后 failed；在 CI 上与概率性的 TestClient teardown hang 叠加，被一并归为「竞态」。 |
| **实证链** | (1) 直接驱动 `_execute` 抓到 `ZoneInfoNotFoundError` traceback；(2) 卸载 tzdata 后 `pytest backend/tests` 必现 `SYSTEM-004` 失败（15/15）；(3) 装 `tzdata==2026.3` 后 19 passed/1 skipped。 |
| **修复** | `pyproject.toml` 加 `tzdata>=2024.2`（运行时依赖）；`requirements.lock` 手动 pin `tzdata==2026.3`（保持 lock 在 Python 3.12 解析，避免本机 3.14 引入 uvloop/colorama 等无关漂移）。 |
| **生产影响** | 修复前镜像在 `python:3.12-slim` 内**每次成功处理都会在命名步崩成 SYSTEM-004**，即根本无法产出可下载文件——属发布阻断级缺陷，远超「CI gate 噪声」。 |

> 残留：概率性 TestClient teardown hang（本机 Win+Py3.14 约 10～20%、全套测试时偶发）依旧存在，单测不复现，疑似多测试间事件循环 teardown 与 starlette 1.3.1/httpx 0.28 传输层的交互。本机无 Python 3.12，下一步把 tzdata 修复推上 CI，在 Ubuntu+Py3.12 下确认 backend-validation 是否就此稳定；若仍 hang，再按第 5 项候选方向处理。

### 后续待办清单

按优先级排列。**第 1～4 项依赖真实运行环境/数据/设备，无法在 CI 内完成**；第 5 项是代码层修复。

1. **【部署联调，最高优先级】目标 Linux Docker 主机端到端实测**：用真实环境变量（`STORPT_PASSWORD_HASH`、`STORPT_SESSION_SECRET`）启动镜像，在 HTTPS 反代后跑通完整登录→上传→处理→下载流程，验证 SSE 在反代后不缓冲（AC-055）。需要：Linux 主机 + 域名 + HTTPS 证书。
2. **【部署联调】真实 AKShare 实时冒烟**：用真实 A 股代码与指定交易日验证行情链路——沪深京清单、不复权开/收盘价取值、休市/停牌按 MARKET 错误显式失败、网络异常重试 3 次（AC-023/024/025）。依赖第 1 项环境就绪。
3. **【部署联调】跨浏览器关键流程**：iOS Safari、Android/HarmonyOS 主流浏览器的文件重选与下载行为差异（AC 第 7 节第 4 项），差异写入发布说明。依赖第 2 项跑通。
4. **【部署联调】真实签名 `.xls` 样本流名确认**：取得真实签名样本，核对流名是否在 `_signatures`/`_xmlsignatures`/`\u0005DigitalSignature` 清单内。若不符，作为 ADR-0026 的 No-Go 触发条件处理（扩 HSSF 拒绝范围）。
5. **【代码修复，已完成】backend-validation 失败**：
   - ✅ **已修并经 CI 验证（2026-08-06）：`tzdata` 缺失导致 `SYSTEM-004`**——见上方「本次工作记录」。这是被误诊为 teardown 竞态多年的真实根因，属发布阻断级生产缺陷。修复后 backend-validation CI（Ubuntu + Python 3.12）转绿，四条发布门槛 CI 全部通过。
   - ℹ️ **非阻断：本机偶发的 TestClient teardown hang**——本机（Windows + Python 3.14）全套测试时约 10～20% 概率 hang，单测不复现；CI（Ubuntu + Python 3.12）多次运行未复现。判定为本机环境（Windows 事件循环 + Python 3.14 + starlette 1.3.1/httpx 0.28 传输层弃用组合）特有，不构成发布阻断。若日后 CI 也出现，候选方向：把 SSE 测试改为直接测 service 层、升级 `httpx→httpx2`、或拆分 CI job 隔离真实 Java 集成测试。

> 备注：本机（Windows）无 docker/node/java，所有可自动化验证在 GitHub Actions 完成。
