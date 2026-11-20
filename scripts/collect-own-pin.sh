#!/usr/bin/env bash
# For each fleet project: its latest completed push CI run on the default
# branch, plus the pin parsed from ci.yml. Prints own-pin.json on stdout.
# GH is the command used for every call (default: gh), split on spaces.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=scripts/lib/version.sh
source "$root/scripts/lib/version.sh"
read -r -a GHC <<< "${GH:-gh}"
fleet=${FLEET_JSON:-$root/fleet.json}
org=jlt-commons
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

warn() { echo "collect-own-pin: $*" >&2; }

# emit <name> <pin|""> <result> <run_url|""> <finished_at|""> <failed_step|"">
emit() {
  jq -n --arg name "$1" --arg pin "$2" --arg result "$3" --arg url "$4" \
        --arg at "$5" --arg step "$6" '
    def nz: if . == "" then null else . end;
    {name: $name, pin: ($pin|nz), result: $result, run_url: ($url|nz),
     finished_at: ($at|nz), failed_step: ($step|nz)}'
}

collect() {
  local p=$1 branch pin="" runs conc result url at id step="" ci="$tmp/ci.yml"
  branch=$("${GHC[@]}" api "repos/$org/$p" --jq .default_branch 2>"$tmp/err") || {
    warn "$p: could not read repository: $(tail -n1 "$tmp/err")"
    emit "$p" "" not-run "" "" ""; return
  }
  if "${GHC[@]}" api -H 'Accept: application/vnd.github.raw' \
       "repos/$org/$p/contents/.github/workflows/ci.yml" >"$ci" 2>"$tmp/err"; then
    pin=$(parse_pin "$ci") || pin=""
  else
    warn "$p: could not read ci.yml: $(tail -n1 "$tmp/err")"
  fi
  runs=$("${GHC[@]}" run list --repo "$org/$p" --workflow ci.yml --branch "$branch" \
           --event push --status completed --limit 1 \
           --json conclusion,url,updatedAt,databaseId 2>"$tmp/err") || {
    warn "$p: could not list runs: $(tail -n1 "$tmp/err")"
    emit "$p" "$pin" not-run "" "" ""; return
  }
  if [ "$(jq length <<<"$runs")" -eq 0 ]; then
    emit "$p" "$pin" not-run "" "" ""; return
  fi
  conc=$(jq -r '.[0].conclusion // "none"' <<<"$runs")
  case "$conc" in
    success)   result=pass ;;
    failure)   result=fail ;;
    timed_out) result=timed-out ;;
    *)         result=not-run ;;
  esac
  url=$(jq -r '.[0].url // ""' <<<"$runs")
  at=$(jq -r '.[0].updatedAt // ""' <<<"$runs")
  if [ "$result" = fail ]; then
    id=$(jq -r '.[0].databaseId' <<<"$runs")
    step=$("${GHC[@]}" run view "$id" --repo "$org/$p" --json jobs \
             --jq '[.jobs[] | select(.conclusion=="failure") | .steps[]? | select(.conclusion=="failure") | .name] | first // ""' \
             2>"$tmp/err") || { warn "$p: could not read jobs: $(tail -n1 "$tmp/err")"; step=""; }
  fi
  emit "$p" "$pin" "$result" "$url" "$at" "$step"
}

while IFS= read -r p; do
  collect "$p"
done < <(jq -r '.projects[]' "$fleet") | jq -s .
