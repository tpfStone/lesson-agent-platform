#!/usr/bin/env bash
set -euo pipefail
source_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for script in "$source_dir"/*.sh; do bash -n "$script"; done
test_root="$(mktemp -d)"
test_pid=''
cleanup() {
  if [[ -n "$test_pid" ]]; then kill "$test_pid" 2>/dev/null || true; fi
  rm -rf -- "$test_root"
}
trap cleanup EXIT
mkdir -p "$test_root/scripts" "$test_root/.runtime/pids"
cp "$source_dir/stop-all.sh" "$source_dir/process-identity.sh" "$test_root/scripts/"
source "$test_root/scripts/process-identity.sh"
sleep 300 &
test_pid=$!
identity="$(process_identity "$test_pid")"
[[ "$identity" == "$(process_identity "$test_pid")" ]]
if process_identity not-a-pid; then exit 1; fi
printf '%s' "$test_pid" > "$test_root/.runtime/pids/f1.pid"
printf 'wrong-identity' > "$test_root/.runtime/pids/f1.identity"
if bash "$test_root/scripts/stop-all.sh"; then
  echo 'Stale identity must be rejected' >&2
  exit 1
fi
kill -0 "$test_pid"
[[ -f "$test_root/.runtime/pids/f1.pid" ]]
printf '%s' "$identity" > "$test_root/.runtime/pids/f1.identity"
bash "$test_root/scripts/stop-all.sh"
if kill -0 "$test_pid" 2>/dev/null; then exit 1; fi
[[ ! -f "$test_root/.runtime/pids/f1.pid" ]]
wait "$test_pid" 2>/dev/null || true
test_pid=''
echo '4 Linux process identity/stop checks and shell syntax passed.'
