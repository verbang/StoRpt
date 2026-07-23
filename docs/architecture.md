# 系统架构设计

> 状态：已确认  
> 更新日期：2026-07-23

## 组件

| 组件 | 技术 | 职责 |
| --- | --- | --- |
| Web/PWA | Vue 3、TypeScript、Vite | 登录、文件选择、表单、进度和下载 |
| API 服务 | Python FastAPI | 认证、任务编排、AKShare、临时文件、错误映射 |
| Excel 工作进程 | Java 21、Apache POI | `.xls/.xlsx` 解析、模板校验、允许单元格写入、自检 |
| 行情适配层 | Python、AKShare | 当前 A 股清单、名称、不复权历史日线 |
| 部署单元 | Docker | 打包前端静态文件、Python 和 Java 运行时 |

## 处理流程

1. PWA 通过 HTTPS 登录并取得安全会话。
2. 分析上传到达 FastAPI 临时目录。
3. FastAPI 调用 Java 工作进程解析模板并返回结构化元数据。
4. FastAPI 删除分析上传文件，将目标表和日期返回前端。
5. 用户点击处理，PWA 重新上传原文件和表单参数。
6. FastAPI 重新校验任务并通过 AKShare 获取证券清单、名称和指定日期行情。
7. FastAPI 将输入文件、目标定位及已确认行情作为结构化参数传给 Java 工作进程。
8. Java 工作进程只写允许单元格，保存同格式输出，并执行工作簿自检。
9. FastAPI 返回下载响应并删除任务临时目录。

## 运行约束

- 同一用户只运行一个任务，状态保存在内存。
- 使用 SSE 推送进度和错误状态。
- 不使用数据库、Redis和消息队列。
- PWA Service Worker 只缓存静态应用资源。
- Excel、行情响应、认证响应及下载文件使用禁止缓存响应头。
- Python 调用 Java 时使用固定可执行文件和参数数组，不拼接 Shell 命令。
- 任务临时目录使用随机标识并限制在指定根目录内。

## 部署

- 单个 Docker 镜像包含前端构建产物、FastAPI、Python 依赖、JRE 和 Excel Worker JAR。
- 运行环境为普通 Linux Docker 主机。
- HTTPS 由部署平台或反向代理终止。
- 环境变量提供访问密码哈希、会话签名密钥和运行配置。
- 不使用无持久临时目录或请求时长受限的纯 Serverless 环境。
