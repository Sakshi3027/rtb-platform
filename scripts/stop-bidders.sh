#!/usr/bin/env bash
# Stops every bidder started by start-bidders.sh. SIGTERM triggers each bidder's graceful shutdown hook.
set -uo pipefail

for pidfile in logs/bidder-*.pid; do
  [ -e "$pidfile" ] || continue
  if kill "$(cat "$pidfile")" 2>/dev/null; then
    echo "stopped $(basename "$pidfile" .pid)"
  fi
  rm -f "$pidfile"
done
