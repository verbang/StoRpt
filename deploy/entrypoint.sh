#!/usr/bin/env sh
# Minimal StoRpt entrypoint: ensure the task workspace is writable, then exec
# uvicorn (CMD) as PID 1 via tini. No supervisor, no background daemons.

set -eu

TASK_ROOT="${STORPT_TASK_ROOT:-/tmp/storpt-tasks}"
mkdir -p "$TASK_ROOT"

# Ownership is normally set at build time, but a bind-mounted task root may
# reset it. Recreate ownership when running as root; as non-root this is a no-op.
if [ "$(id -u)" = "0" ]; then
    chown -R storpt:storpt "$TASK_ROOT"
fi

exec "$@"
