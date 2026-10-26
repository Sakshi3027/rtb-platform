#!/usr/bin/env bash
# Starts N bidder instances on consecutive ports (default 3, from 8081), all sharing Redis budgets.
# Usage: scripts/start-bidders.sh [count]
set -euo pipefail

COUNT="${1:-3}"
BASE_PORT="${BASE_PORT:-8081}"
JAR="bidder/target/bidder-0.1.0-SNAPSHOT.jar"

mkdir -p logs
for i in $(seq 0 $((COUNT - 1))); do
  port=$((BASE_PORT + i))
  BIDDER_PORT="$port" \
  REDIS_URI="${REDIS_URI:-redis://localhost:6379}" \
  PACING="${PACING:-even}" \
    nohup java -Xmx512m -jar "$JAR" > "logs/bidder-$port.log" 2>&1 &
  echo $! > "logs/bidder-$port.pid"
  echo "started bidder on port $port (pid $!)"
done
