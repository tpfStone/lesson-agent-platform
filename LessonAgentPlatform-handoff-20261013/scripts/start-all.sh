#!/usr/bin/env bash
set -Eeuo pipefail

SKIP_FRONTEND=0
if [[ "${1:-}" == "--skip-frontend" ]]; then
  SKIP_FRONTEND=1
elif [[ $# -gt 0 ]]; then
  echo "Usage: $0 [--skip-frontend]" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"
RUNTIME="$ROOT/.runtime"
LOGS="$RUNTIME/logs"
PIDS="$RUNTIME/pids"

load_env() {
  [[ -f "$ENV_FILE" ]] || { echo "Missing $ENV_FILE" >&2; exit 1; }
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    raw="${raw%$'\r'}"
    [[ -z "${raw//[[:space:]]/}" || "$raw" =~ ^[[:space:]]*# ]] && continue
    [[ "$raw" == *=* ]] || { echo "Invalid .env line: $raw" >&2; exit 1; }
    key="${raw%%=*}"
    value="${raw#*=}"
    key="${key//[[:space:]]/}"
    export "$key=$value"
  done < "$ENV_FILE"
}

require_env() {
  local name="$1" value="${!1:-}"
  [[ -n "$value" && "$value" != replace-* ]] || {
    echo "Configure $name in $ENV_FILE before starting." >&2
    exit 1
  }
}

require_file() {
  [[ -f "$1" ]] || { echo "Missing file: $1" >&2; exit 1; }
}

port_busy() {
  timeout 1 bash -c "</dev/tcp/127.0.0.1/$1" >/dev/null 2>&1
}

start_one() {
  local name="$1" workdir="$2"
  shift 2
  (
    cd "$workdir"
    nohup setsid "$@" >"$LOGS/$name.log" 2>&1 </dev/null &
    echo $! >"$PIDS/$name.pid"
  )
  echo "Started $name PID=$(cat "$PIDS/$name.pid")"
}

load_env
for name in DEEPSEEK_API_KEY ENGINE_INTERNAL_TOKEN DB_URL DB_USERNAME DB_PASSWORD; do
  require_env "$name"
done
if [[ -z "${API_KEY:-}" || "${API_KEY:-}" == replace-* ]]; then export API_KEY="$DEEPSEEK_API_KEY"; fi
if [[ -z "${LLM_API_KEY:-}" || "${LLM_API_KEY:-}" == replace-* ]]; then export LLM_API_KEY="$DEEPSEEK_API_KEY"; fi

F1_DIR="$ROOT/System-v1.2"
ENGINE_DIR="$ROOT/Lessongen-main/paper4_pipeline"
F3_DIR="$ROOT/SimClass-main"
F4_DIR="$ROOT/NoviceTeacher-AI-main/backend"
WEB_DIR="$ROOT/Lessongen-main/web"
FRONTEND_DIR="$WEB_DIR/frontend"

F1_PY="$F1_DIR/.venv/bin/python"
ENGINE_PY="$ENGINE_DIR/.venv/bin/python"
F3_PY="$F3_DIR/.venv/bin/python"
F4_PY="$F4_DIR/.venv/bin/python"
require_file "$F1_PY"
require_file "$ENGINE_PY"
require_file "$F3_PY"
require_file "$F4_PY"
require_file "$F1_DIR/platform_api.py"
require_file "$WEB_DIR/scripts/f3-runner/run_f3_demo.py"
if (( SKIP_FRONTEND == 0 )) && [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
  echo 'Frontend node_modules is missing. Run npm ci first.' >&2
  exit 1
fi

export F3_PYTHON="$F3_PY"
export PLATFORM_F3_RUNNER_SCRIPT="$WEB_DIR/scripts/f3-runner/run_f3_demo.py"
export PYTHONPATH="$ENGINE_DIR/src${PYTHONPATH:+:$PYTHONPATH}"

ports=(8000 8001 8002 8003 8080)
(( SKIP_FRONTEND == 1 )) || ports+=(5177)
for port in "${ports[@]}"; do
  if port_busy "$port"; then
    echo "Port $port is already in use. Stop the existing service first." >&2
    exit 1
  fi
done

mkdir -p "$LOGS" "$PIDS"
if find "$PIDS" -name '*.pid' -type f -print -quit | grep -q .; then
  echo "PID files already exist in $PIDS. Run stop-all.sh first." >&2
  exit 1
fi

echo 'Applying F4 migrations...'
(cd "$F4_DIR" && "$F4_PY" -m alembic -c alembic.ini upgrade head)

start_one f1 "$F1_DIR" "$F1_PY" -m uvicorn platform_api:app --host 127.0.0.1 --port 8002 --http h11
start_one f2-engine "$ENGINE_DIR" "$ENGINE_PY" -m paper4_pipeline.web_api
start_one f3 "$F3_DIR" "$F3_PY" -m uvicorn app.api.classroom_api:app --host 127.0.0.1 --port 8003
start_one f4 "$F4_DIR" "$F4_PY" -m uvicorn app.api:app --host 127.0.0.1 --port 8000

jar="$(find "$WEB_DIR/target" -maxdepth 1 -type f -name '*.jar' ! -name '*.original' 2>/dev/null | head -n 1 || true)"
if [[ -n "$jar" ]]; then
  start_one spring "$WEB_DIR" java -jar "$jar"
else
  require_file "$WEB_DIR/mvnw"
  start_one spring "$WEB_DIR" bash ./mvnw spring-boot:run
fi

if (( SKIP_FRONTEND == 0 )); then
  start_one frontend "$FRONTEND_DIR" npm run dev -- --host 127.0.0.1
fi

echo 'Waiting for services...'
if (( SKIP_FRONTEND == 1 )); then
  "$ROOT/scripts/health-check.sh" --skip-frontend
else
  "$ROOT/scripts/health-check.sh"
fi

echo 'All requested services are healthy.'
(( SKIP_FRONTEND == 1 )) || echo 'Open http://127.0.0.1:5177'

