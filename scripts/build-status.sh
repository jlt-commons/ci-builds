#!/usr/bin/env bash
# Pure merge: fan-out results + own-pin + uncovered + previous status/history
# -> new status.json and history.json. No network, no git.
set -euo pipefail

usage() {
  echo "usage: build-status.sh --prev-status F --prev-history F --own-pin F --uncovered F" >&2
  echo "       --latest VERSION --nightly-json JSON|null --canary-run URL --now ISO --out-dir D [results.json ...]" >&2
  exit 2
}

prev_status="" prev_history="" own_pin="" uncovered="" latest="" nightly="" canary="" now="" out=""
nightly_set=0
while [ $# -gt 0 ]; do
  case "$1" in
    --prev-status)  [ $# -ge 2 ] || usage; prev_status=$2; shift 2 ;;
    --prev-history) [ $# -ge 2 ] || usage; prev_history=$2; shift 2 ;;
    --own-pin)      [ $# -ge 2 ] || usage; own_pin=$2; shift 2 ;;
    --uncovered)    [ $# -ge 2 ] || usage; uncovered=$2; shift 2 ;;
    --latest)       [ $# -ge 2 ] || usage; latest=$2; shift 2 ;;
    --nightly-json) [ $# -ge 2 ] || usage; nightly=$2; nightly_set=1; shift 2 ;;
    --canary-run)   [ $# -ge 2 ] || usage; canary=$2; shift 2 ;;
    --now)          [ $# -ge 2 ] || usage; now=$2; shift 2 ;;
    --out-dir)      [ $# -ge 2 ] || usage; out=$2; shift 2 ;;
    --) shift; break ;;
    -*) usage ;;
    *) break ;;
  esac
done
results=("$@")
[ -n "$own_pin" ] && [ -n "$uncovered" ] && [ -n "$latest" ] && [ "$nightly_set" -eq 1 ] \
  && [ -n "$canary" ] && [ -n "$now" ] && [ -n "$out" ] || usage

latest=${latest#v}
mkdir -p "$out"
tmp=$(mktemp -d "$out/.build-status.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

# A missing previous file counts as empty (null).
slurp_or_null() { if [ -n "$1" ] && [ -f "$1" ]; then cp "$1" "$2"; else echo null > "$2"; fi; }
slurp_or_null "$prev_status" "$tmp/prev-status.json"
slurp_or_null "$prev_history" "$tmp/prev-history.json"

if [ ${#results[@]} -gt 0 ]; then
  jq -s '.' "${results[@]}" > "$tmp/results.json"
else
  echo '[]' > "$tmp/results.json"
fi

jq -n \
  --slurpfile prev "$tmp/prev-status.json" \
  --slurpfile prevh "$tmp/prev-history.json" \
  --slurpfile files "$tmp/results.json" \
  --slurpfile own "$own_pin" \
  --slurpfile unc "$uncovered" \
  --arg latest "$latest" \
  --argjson nj "$nightly" \
  --arg canary "$canary" \
  --arg now "$now" '
  def strip: if type == "string" then sub("^v"; "") else . end;
  def valid: ["pass","fail","infra","timed-out","dispatch-failed","mismatch","not-run"];
  def notrun: {result: "not-run"};
  def cell($f): {
    result: .result, version: ($f.requested | strip), installed: (.installed // null),
    run_url: (.run_url // null), finished_at: (.finished_at // null),
    failed_step: (.failed_step // null)};
  def column($f):
    if $f.requested == "nightly" then "nightly"
    elif ($f.requested | strip) == $latest then "latest"
    else "adhoc" end;

  ($prev[0]) as $ps | ($prevh[0]) as $ph | $files[0] as $fs | $own[0] as $op | $unc[0] as $un
  | (($op | type) == "array" and all($op[]; (.name | type) == "string" and (.pin | type) == "string")
     or error("malformed own-pin")) as $_
  | (($un | type) == "array" or error("malformed uncovered")) as $_
  | (($ps == null or ($ps | type) == "object") or error("malformed prev status")) as $_
  | (($ph == null or ($ph | type) == "object") or error("malformed prev history")) as $_
  | (all($fs[]; type == "object" and (.requested | type) == "string" and (.projects | type) == "array"
        and all(.projects[]; (.name | type) == "string" and (.result as $r | valid | any(. == $r))))
     or error("malformed results")) as $_

  # results this run, in file order
  | [ $fs[] as $f | $f.projects[] | {name, col: column($f), cell: cell($f)} ] as $res

  | (if $ps == null then false else ($ps.jolt.latest.version // null) != $latest end) as $latest_stale
  | (if $nj == null then false else ($ps.jolt.nightly.sha // null) != $nj.sha end) as $nightly_stale
  | ((($ps.projects // []) | map({key: .name, value: .cells}) | from_entries)) as $pc
  | ($res | map(select(.col != "adhoc")) | reduce .[] as $e ({}; .[$e.name + "/" + $e.col] = $e.cell)) as $new

  | def pick($n; $c; $stale):
      $new[$n + "/" + $c]
      // (if $stale then notrun else (($pc[$n] // {})[$c] // notrun) end);

  [ $op[] | . as $p
    | {name: $p.name, pin: $p.pin,
       cells: {
         pin: {result: $p.result, version: $p.pin, installed: null,
               run_url: ($p.run_url // null), finished_at: ($p.finished_at // null),
               failed_step: ($p.failed_step // null)},
         latest: pick($p.name; "latest"; $latest_stale),
         nightly: pick($p.name; "nightly"; $nightly_stale)}} ] as $projects

  | ($projects | map(select(.cells | [.[] | .result | . != "pass" and . != "not-run"] | any)) | map(.name)) as $bad
  | ($projects | sort_by([(.name as $n | if $bad | any(. == $n) then 0 else 1 end), .name])) as $sorted

  # history: results this run, then the pin cell when its run is new
  | ($res + [ $projects[] | . as $p | {name: $p.name, col: "pin", cell: $p.cells.pin}
       | select(((($ph.projects // {})[.name].pin // [])[0].run_url // "__none__") != (.cell.run_url // "__none__")
                or ((($ph.projects // {})[.name].pin // []) | length) == 0) ]) as $entries
  | ($entries | reduce .[] as $e ($ph.projects // {};
      if $e.cell.run_url != null and ((.[$e.name][$e.col] // []) | any(.run_url == $e.cell.run_url))
      then .
      else .[$e.name][$e.col] = ([$e.cell] + (.[$e.name][$e.col] // []))[0:30] end)
    | map_values({pin: [], latest: [], nightly: [], adhoc: []} + .)) as $hist

  | {status: {schema: 1, generated_at: $now, canary_run: $canary,
              jolt: {latest: {version: $latest}, nightly: $nj},
              projects: $sorted, uncovered: $un},
     history: {schema: 1, projects: $hist}}
' > "$tmp/both.json"

jq '.status' "$tmp/both.json" > "$tmp/status.json"
jq '.history' "$tmp/both.json" > "$tmp/history.json"
mv "$tmp/status.json" "$out/status.json"
mv "$tmp/history.json" "$out/history.json"
