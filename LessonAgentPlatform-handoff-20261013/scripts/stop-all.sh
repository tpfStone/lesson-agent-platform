#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIDS="$ROOT/.runtime/pids"

if [[ ! -d "$PIDS" ]]; then
  echo 'No PID directory found. Nothing was stopped.'
  exit 0
fi

services=(frontend spring f4 f3 f2-engine f1)
for name in "${services[@]}"; do
  file="$PIDS/$name.pid"
  [[ -f "$file" ]] || continue
  pid="$(cat "$file")"
  if kill -0 "$pid" 2>/dev/null; then
    echo "Stopping $name PID=$pid"
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    for _ in {1..20}; do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.5
    done
    if kill -0 "$pid" 2>/dev/null; then
      kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
    fi
  fi
  rm -f "$file"
done

rmdir "$PIDS" 2>/dev/null || true
echo 'All recorded services have been stopped.'

