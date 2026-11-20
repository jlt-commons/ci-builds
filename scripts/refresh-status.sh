#!/usr/bin/env bash
# Collect and build the fleet status files; publish-status.sh calls this as its
# REBUILD command, and so does the ad-hoc publish job in fanout.yml.
#
# usage: refresh-status.sh <out-dir> [results.json ...]
# env:   LATEST       jolt's latest release (a leading v is fine)
#        NIGHTLY_JSON compact {"published_at","sha","note"} or the literal null
#        CANARY_RUN   URL of the run that is publishing
#        PREV_DIR     directory holding the previous status.json/history.json
#                     (may be absent or empty); set by publish-status.sh
#        GH_TOKEN, GH, FLEET_JSON as for the collectors
set -euo pipefail

[ $# -ge 1 ] || { echo "usage: refresh-status.sh <out-dir> [results.json ...]" >&2; exit 2; }
for v in LATEST NIGHTLY_JSON CANARY_RUN; do
  [ -n "${!v:-}" ] || { echo "refresh-status: $v is required" >&2; exit 2; }
done
out=$1; shift
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
prev=${PREV_DIR:-/nonexistent}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

"$root/scripts/collect-own-pin.sh" > "$tmp/own-pin.json"
"$root/scripts/collect-uncovered.sh" > "$tmp/uncovered.json"

"$root/scripts/build-status.sh" \
  --prev-status "$prev/status.json" --prev-history "$prev/history.json" \
  --own-pin "$tmp/own-pin.json" --uncovered "$tmp/uncovered.json" \
  --latest "$LATEST" --nightly-json "$NIGHTLY_JSON" --canary-run "$CANARY_RUN" \
  --now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --out-dir "$out" "$@"
