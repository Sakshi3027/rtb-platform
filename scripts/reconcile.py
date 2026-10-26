#!/usr/bin/env python3
"""Reconciles campaign spend across a bidder fleet against Redis.

Run after a load test while the bidders are still running and idle.
Checks, for every campaign:
  1. Redis spent <= Redis total                     (no overspend)
  2. Redis spent == sum of micros granted to instances  (every leased micro has one owner)
  3. granted == reserved + lease remaining          (nothing lost inside an instance)
Exits non-zero if any check fails.

Usage: python3 scripts/reconcile.py [port ...]   (default: 8081 8082 8083)
"""
import json
import subprocess
import sys
import urllib.request

PORTS = [int(port) for port in sys.argv[1:]] or [8081, 8082, 8083]


def fetch_stats(port):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/stats", timeout=5) as response:
        return json.load(response)


def redis(*args):
    result = subprocess.run(
        ["docker", "exec", "rtb-redis", "redis-cli", *args],
        capture_output=True, text=True, check=True)
    return result.stdout.split()


def dollars(micros):
    return f"${micros / 1_000_000:,.4f}"


instances = {port: fetch_stats(port) for port in PORTS}
first = next(iter(instances.values()))
max_lease_per_campaign = first["leaseChunkMicros"] + first["lowWaterMicros"]

keys = sorted(redis("--scan", "--pattern", "budget:*"))
failures = []
totals = {"budget": 0, "spent": 0, "reserved": 0, "stranded": 0}

print(f"{'campaign':<20}{'budget':>12}{'redis spent':>14}{'reserved':>14}{'stranded':>12}")
for key in keys:
    campaign = key.split(":")[-1]
    total, spent = (int(value) for value in redis("HMGET", key, "total", "spent"))

    per_instance = [stats["campaigns"].get(campaign, {}) for stats in instances.values()]
    granted = sum(s.get("grantedMicros", 0) for s in per_instance)
    reserved = sum(s.get("reservedMicros", 0) for s in per_instance)
    stranded = sum(s.get("leaseRemainingMicros", 0) for s in per_instance)

    if spent > total:
        failures.append(f"{campaign}: OVERSPEND, spent {spent} > total {total}")
    if spent != granted:
        failures.append(f"{campaign}: LEDGER MISMATCH, redis spent {spent} != granted {granted}")
    if reserved + stranded != granted:
        failures.append(f"{campaign}: INSTANCE MISMATCH, reserved+stranded {reserved + stranded} != granted {granted}")

    print(f"{campaign:<20}{dollars(total):>12}{dollars(spent):>14}{dollars(reserved):>14}{dollars(stranded):>12}")
    totals["budget"] += total
    totals["spent"] += spent
    totals["reserved"] += reserved
    totals["stranded"] += stranded

stranded_bound = len(PORTS) * len(keys) * max_lease_per_campaign
print()
print(f"instances            {len(PORTS)}")
print(f"total budget         {dollars(totals['budget'])}")
print(f"leased from redis    {dollars(totals['spent'])}")
print(f"reserved by bids     {dollars(totals['reserved'])}")
print(f"stranded in leases   {dollars(totals['stranded'])}  (bound {dollars(stranded_bound)})")
print(f"overspend            {dollars(max(0, totals['spent'] - totals['budget']))}")

if totals["stranded"] > stranded_bound:
    failures.append(f"stranded {totals['stranded']} exceeds bound {stranded_bound}")

if failures:
    print("\nFAILED")
    for failure in failures:
        print("  " + failure)
    sys.exit(1)
print("\nALL CHECKS PASSED: ledger reconciles exactly across the fleet")
