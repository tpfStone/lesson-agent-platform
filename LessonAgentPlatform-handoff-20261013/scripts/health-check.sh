#!/usr/bin/env bash
set -u

SKIP_FRONTEND=0
if [[ "${1:-}" == "--skip-frontend" ]]; then SKIP_FRONTEND=1; fi

names=(F4 F2Engine F1 F3 Spring)
urls=(
  'http://127.0.0.1:8000/api/health'
  'http://127.0.0.1:8001/internal/v1/health'
  'http://127.0.0.1:8002/internal/v1/health'
  'http://127.0.0.1:8003/api/health'
  'http://127.0.0.1:8080/actuator/health'
)
if (( SKIP_FRONTEND == 0 )); then
  names+=(Frontend)
  urls+=('http://127.0.0.1:5177/')
fi

retries="${HEALTH_RETRIES:-45}"
delay="${HEALTH_DELAY_SECONDS:-2}"
for ((attempt=1; attempt<=retries; attempt++)); do
  failed=0
  results=()
  for i in "${!urls[@]}"; do
    code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 "${urls[$i]}" 2>/dev/null || true)"
    if [[ "$code" =~ ^2 ]]; then
      results+=("${names[$i]}|UP|$code|${urls[$i]}")
    else
      results+=("${names[$i]}|DOWN|${code:-0}|${urls[$i]}")
      failed=1
    fi
  done
  (( failed == 0 )) && break
  (( attempt < retries )) && sleep "$delay"
done

printf '%-12s %-6s %-6s %s\n' SERVICE STATUS HTTP URL
for row in "${results[@]}"; do
  IFS='|' read -r name status code url <<<"$row"
  printf '%-12s %-6s %-6s %s\n' "$name" "$status" "$code" "$url"
done
exit "$failed"

