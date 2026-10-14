#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/process-identity.sh"
PIDS="$ROOT/.runtime/pids"

if [[ ! -d "$PIDS" ]]; then
  echo 'No PID directory found. Nothing was stopped.'
  exit 0
fi

services=(frontend spring f4 f3 f2-engine f1)
unverified=0
for name in "${services[@]}"; do
  file="$PIDS/$name.pid"
  [[ -f "$file" ]] || continue
  pid="$(cat "$file")"
  if [[ ! "$pid" =~ ^[0-9]+$ || ! -f "$PIDS/$name.identity" ]]; then
    echo "Unverified PID entry for $name; nothing stopped." >&2
    unverified=1; continue
  fi
  if kill -0 "$pid" 2>/dev/null; then
    if [[ "$(process_identity "$pid")" != "$(cat "$PIDS/$name.identity")" ]]; then
      echo "PID identity changed for $name; nothing stopped." >&2
      unverified=1; continue
    fi
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
  rm -f "$file" "$PIDS/$name.identity" "$PIDS/$name.command"
done

rmdir "$PIDS" 2>/dev/null || true
echo 'All recorded services have been stopped.'
exit "$unverified"
