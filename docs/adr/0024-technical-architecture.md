# ADR-0024：采用 FastAPI 与 Apache POI 单镜像架构

- 状态：已接受
- 日期：2026-07-23

## 背景

AKShare 适合 Python 接入，但 Python 常用库难以同时高保真处理 `.xls` 和 `.xlsx`。产品又是个人低频工具，不适合引入数据库、消息队列和多个网络服务。

## 决策

- 前端使用 Vue 3、TypeScript、Vite 和 PWA。
- 主后端使用 Python FastAPI，并在 Python 中封装 AKShare。
- Excel 处理使用 Java 21 和 Apache POI，本地子进程同时支持 `.xls` 与 `.xlsx`。
- Python 与 Java 通过临时文件及结构化参数通信，不建立独立网络微服务。
- 使用 SSE 推送进度。
- 不使用数据库、Redis和消息队列。
- 前端、Python、JRE和 Excel Worker 打包为一个 Docker 镜像。
- 部署在提供 HTTPS 的普通 Linux Docker 主机，不使用纯 Serverless。

## 影响

- 镜像体积大于纯 Python 应用，但部署单元仍然只有一个。
- 需要分别测试 Apache POI 对 HSSF `.xls` 和 XSSF `.xlsx` 的保真能力。
- Java Worker 必须使用单元格级白名单，并在保存后重新打开文件执行自检。
