#!/usr/bin/env python3
"""Imports campaigns into the campaign service, creating each one only if it does not exist yet."""
import json
import sys
import urllib.error
import urllib.request

SERVICE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8090"
SOURCE = sys.argv[2] if len(sys.argv) > 2 else "bidder/src/main/resources/campaigns.json"

with open(SOURCE) as f:
    campaigns = json.load(f)

for campaign in campaigns:
    request = urllib.request.Request(
        f"{SERVICE}/campaigns/{campaign['id']}",
        data=json.dumps(campaign).encode(),
        method="PUT",
        headers={"Content-Type": "application/json", "If-None-Match": "*"},
    )
    try:
        with urllib.request.urlopen(request) as response:
            print(f"{campaign['id']:<20} {response.status} created, ETag {response.headers['ETag']}")
    except urllib.error.HTTPError as e:
        print(f"{campaign['id']:<20} {e.code} {e.read().decode()}")
