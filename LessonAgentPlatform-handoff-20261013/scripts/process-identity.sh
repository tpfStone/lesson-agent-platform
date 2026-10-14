#!/usr/bin/env bash
process_identity() {
  local pid="$1" stat rest
  [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/stat" ]] || return 1
  stat="$(cat "/proc/$pid/stat")" || return 1
  rest="${stat##*) }"
  local fields=()
  read -r -a fields <<<"$rest"
  [[ ${#fields[@]} -gt 19 ]] || return 1
  printf '%s:%s:%s' "$(cat /proc/sys/kernel/random/boot_id)" "$pid" "${fields[19]}"
}
