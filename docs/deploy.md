# 部署说明

> 状态：草稿  
> 更新日期：2026-07-26

本文档描述如何构建 StoRpt 单镜像、在普通 Linux Docker 主机运行，以及反向代理与升级流程。架构基线见 [`architecture.md`](architecture.md) 与 [ADR-0024](adr/0024-technical-architecture.md)。

## 1. 镜像构成

`deploy/Dockerfile` 是一个三阶段单镜像：

| 阶段 | 基础镜像 | 产物 |
| --- | --- | --- |
| `frontend-builder` | `node:24-alpine` | `frontend/dist/` 静态文件 |
| `worker-builder` | `eclipse-temurin:21-jdk-noble` | shaded `excel-worker.jar` |
| `runtime` | `python:3.12-slim` | FastAPI + JRE + Worker + 静态文件 |

运行时镜像内：

- 前端静态文件由 FastAPI 自服务（`StaticFiles` 挂载在 `/`），见 [`backend/storpt_api/main.py`](../backend/storpt_api/main.py)。
- Worker 通过子进程 `java -jar /app/excel-worker.jar` 调用。
- 以非 root 用户 `storpt` 运行，`tini` 作为 PID 1。
- 监听 `8000`，**HTTPS 由外部反代终止**。

## 2. 构建镜像

镜像从提交入仓的锁文件复现构建：

```sh
docker build -f deploy/Dockerfile -t storpt:latest .
```

锁文件首次缺失时，CI（`Docker image validation` 工作流）会在 runner 上现生成。要本地提交锁文件，手动触发 **Generate lock files** 工作流，下载 artifact，把 `backend/requirements.lock` 与 `frontend/package-lock.json` commit 入仓。

## 3. 运行所需环境变量

| 变量 | 必需 | 说明 |
| --- | :---: | --- |
| `STORPT_PASSWORD_HASH` | 是 | `scrypt$...` 格式密码哈希，生成方式见 [`README.md`](../README.md#authentication-configuration)。 |
| `STORPT_SESSION_SECRET` | 是 | 任意长随机串，用于签名 7 天会话 Cookie。 |
| `STORPT_TASK_ROOT` | 否 | 任务临时目录，默认 `/tmp/storpt-tasks`，需对 `storpt` 用户可写。 |
| `STORPT_STATIC_DIR` | 否 | 前端静态目录，默认 `/app/static`。 |
| `STORPT_WORKER_JAR` | 否 | Worker JAR 路径，默认 `/app/excel-worker.jar`。 |

任一必需变量缺失时 `/healthz` 仍可用，但登录失败闭合返回 `SYSTEM-003`。

### 启动

```sh
docker run -d --name storpt \
  -p 8000:8000 \
  -e STORPT_PASSWORD_HASH='scrypt$...' \
  -e STORPT_SESSION_SECRET='$(openssl rand -hex 32)' \
  --restart unless-stopped \
  storpt:latest
```

## 4. HTTPS 反向代理

会话 Cookie 为 `Secure; SameSite=strict`，必须在 HTTPS 后提供服务，且反代需透传 `X-Forwarded-Proto`。

### Caddy

```caddy
storpt.example.com {
    reverse_proxy localhost:8000
}
```

### nginx

```nginx
server {
    listen 443 ssl http2;
    server_name storpt.example.com;

    # ssl_certificate     /etc/ssl/storpt/fullchain.pem;
    # ssl_certificate_key /etc/ssl/storpt/privkey.pem;

    client_max_body_size 11m;   # 10 MB 文件上限 + multipart 开销

    location / {
        proxy_pass         http://127.0.0.1:8000;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;

        # SSE 进度流不被缓冲
        proxy_buffering    off;
        proxy_read_timeout 200s;
    }
}
```

## 5. 备份与升级

- **无持久状态**：StoRpt 不使用数据库；所有任务状态在内存与临时目录中。备份只需保留部署环境变量。
- **升级**：拉取新镜像 → `docker stop storpt && docker rm storpt` → 用相同环境变量重新 `docker run`。运行中的任务会被中断（按 AC-053 临时文件最迟 10 分钟清理）。
- **配置变更**：修改环境变量需重建/重启容器。

## 6. 已知限制

- `.xls` 格式的产品能力仍待真实 Excel/WPS 样本验证（见 [`technical-validation.md`](technical-validation.md)）。
- 移动端浏览器（iOS Safari、Android/HarmonyOS）的文件保存行为差异未在本阶段验证。
- AKShare 依赖东方财富等第三方接口，可能因限流或字段变化导致行情任务失败；失败按 `MARKET` 错误显式上报，不静默降级。

## 7. CI 验证

四条工作流构成发布门槛，任一失败不得发布：

| 工作流 | 覆盖 |
| --- | --- |
| Excel technical validation | Apache POI 模板与保真 |
| FastAPI backend validation | 后端编排与 Worker 集成 |
| PWA frontend validation | Vue 类型检查与构建 |
| **Docker image validation** | 单镜像构建与 `/healthz`、鉴权、SPA 冒烟 |
