#!/usr/bin/env bash
# Public, non-archived org repos that the fleet does not cover, each with a
# reason. Prints uncovered.json on stdout.
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

command -v yq >/dev/null 2>&1 || { echo "collect-uncovered: yq is required but not on PATH" >&2; exit 1; }

warn() { echo "collect-uncovered: $*" >&2; }

reason_for() {
  local out p=$1 in_fleet=0 excluded has_ci=0 has_input=0 ci="$tmp/ci.yml"
  if jq -e --arg p "$p" '.projects | index($p)' "$fleet" >/dev/null; then in_fleet=1; fi
  excluded=$(jq -r --arg p "$p" '.excluded[$p] // ""' "$fleet")
  if [ "$in_fleet" = 0 ] && [ -z "$excluded" ]; then
    if "${GHC[@]}" api -H 'Accept: application/vnd.github.raw' \
         "repos/$org/$p/contents/.github/workflows/ci.yml" >"$ci" 2>"$tmp/err"; then
      has_ci=1
      # YAML 1.1 readers may turn the "on" key into boolean true.
      if ! out=$(yq '(.on // .true) | select(tag == "!!map") | (.workflow_dispatch.inputs // {}) | has("jolt-version")' "$ci" 2>"$tmp/err"); then
        warn "$p: could not parse ci.yml: $(tail -n1 "$tmp/err")"
        printf 'could not read repository\n'; return
      fi
      [ "$out" = true ] && has_input=1
    elif grep -qE '\(HTTP 404\)[[:space:]]*$' "$tmp/err"; then
      has_ci=0
    else
      warn "$p: could not read repository: $(tail -n1 "$tmp/err")"
      printf 'could not read repository\n'; return
    fi
  fi
  uncovered_reason "$p" "$has_ci" "$has_input" "$in_fleet" "$excluded"
}

names=$("${GHC[@]}" api --paginate "orgs/$org/repos?type=public&per_page=100" \
  --jq '.[] | select((.archived|not) and (.private|not)) | .name')

while IFS= read -r p; do
  [ -n "$p" ] || continue
  r=$(reason_for "$p")
  [ -n "$r" ] || continue
  jq -n --arg name "$p" --arg reason "$r" '{name: $name, reason: $reason}'
done <<<"$names" | jq -s .
