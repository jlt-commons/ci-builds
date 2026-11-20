setup() {
  F="$BATS_TEST_DIRNAME/fixtures/status"
  S="$BATS_TEST_DIRNAME/../scripts/build-status.sh"
  OUT="$BATS_TEST_TMPDIR/out"
  NJ='{"published_at":"2026-11-20T01:00:00Z","sha":"f4e82fc8eac874cae1658b7976518253027c4f6a","note":"n"}'
}

# run_bs [extra args] -- results files: uses fixtures for own-pin/uncovered; prev files via PS/PH
run_bs() {
  local nj=${NJ_ARG-$NJ} latest=${LATEST:-0.8.27}
  run "$S" --prev-status "${PS:-/nonexistent}" --prev-history "${PH:-/nonexistent}" \
    --own-pin "${OP:-$F/own-pin.json}" --uncovered "$F/uncovered.json" \
    --latest "$latest" --nightly-json "$nj" --canary-run https://example/run/1 \
    --now 2026-11-20T04:00:00Z --out-dir "$OUT" "$@"
}
st() { jq -c "$1" "$OUT/status.json"; }
hi() { jq -c "$1" "$OUT/history.json"; }

@test "first run with no prev files" {
  run_bs "$F/results-latest.json"
  [ "$status" -eq 0 ]
  [ "$(st '.schema')" = 1 ]
  [ "$(st '.projects|map(.name)|sort')" = '["ebb","glitter","raygui-jlt"]' ]
  [ "$(st '.projects[]|select(.name=="raygui-jlt").cells.latest')" = '{"result":"not-run"}' ]
  [ "$(st '.projects[]|select(.name=="raygui-jlt").cells.nightly')" = '{"result":"not-run"}' ]
  [ "$(st '.uncovered')" = '[{"name":"duratom","reason":"ci.yml has no jolt-version input"}]' ]
  [ "$(st '.generated_at')" = '"2026-11-20T04:00:00Z"' ]
  [ "$(hi '.schema')" = 1 ]
  [ "$(hi '.projects.glitter.latest|length')" = 1 ]
  [ "$(hi '.projects.ebb.latest|length')" = 1 ]
  [ "$(hi '.projects.glitter.pin|length')" = 1 ]
}

@test "pin cell comes from own-pin with installed null" {
  run_bs
  [ "$(st '.projects[]|select(.name=="glitter").cells.pin|[.result,.version,.installed]')" = '["pass","0.8.24",null]' ]
}

@test "latest result lands in latest column" {
  run_bs "$F/results-latest.json"
  [ "$(st '.projects[]|select(.name=="glitter").cells.latest|[.result,.version,.installed]')" = '["pass","0.8.27","0.8.27"]' ]
  [ "$(st '.jolt.latest')" = '{"version":"0.8.27"}' ]
}

@test "v prefix on requested is stripped" {
  jq '.requested="v0.8.27"' "$F/results-latest.json" > "$BATS_TEST_TMPDIR/r.json"
  run_bs "$BATS_TEST_TMPDIR/r.json"
  [ "$(st '.projects[]|select(.name=="glitter").cells.latest.version')" = '"0.8.27"' ]
}

@test "nightly result lands in nightly column" {
  run_bs "$F/results-nightly.json"
  [ "$(st '.projects[]|select(.name=="glitter").cells.nightly|[.result,.version,.installed]')" = '["pass","nightly","0.8.27-376-gf4e82fc8"]' ]
  [ "$(st '.jolt.nightly.sha')" = '"f4e82fc8eac874cae1658b7976518253027c4f6a"' ]
}

@test "adhoc result only in history" {
  PS="$F/prev-status.json" PH="$F/prev-history.json" run_bs "$F/results-adhoc.json"
  [ "$status" -eq 0 ]
  [ "$(st '.projects[]|select(.name=="glitter").cells.latest.run_url')" = '"https://github.com/jlt-commons/glitter/actions/runs/42"' ]
  [ "$(hi '.projects.glitter.adhoc|length')" = 1 ]
  [ "$(hi '.projects.glitter.adhoc[0].version')" = '"0.8.25"' ]
  [ "$(hi '.projects.glitter.latest|length')" = 0 ]
}

@test "results for a project outside own-pin go to history, not the grid" {
  jq '.projects[0].name="gone"' "$F/results-latest.json" > "$BATS_TEST_TMPDIR/r.json"
  run_bs "$BATS_TEST_TMPDIR/r.json"
  [ "$(st '.projects|map(.name)|index("gone")')" = null ]
  [ "$(hi '.projects.gone.latest|length')" = 1 ]
}

@test "carry over with only own-pin input" {
  NJ_ARG='{"published_at":"x","sha":"aaaa1111","note":"n"}' PS="$F/prev-status.json" PH="$F/prev-history.json" run_bs
  [ "$status" -eq 0 ]
  [ "$(st '.projects[]|select(.name=="ebb").cells.latest')" = "$(jq -c '.projects[]|select(.name=="ebb").cells.latest' "$F/prev-status.json")" ]
  [ "$(st '.projects[]|select(.name=="glitter").cells.nightly')" = "$(jq -c '.projects[]|select(.name=="glitter").cells.nightly' "$F/prev-status.json")" ]
  # raygui-jlt had no prior cells
  [ "$(st '.projects[]|select(.name=="raygui-jlt").cells.latest')" = '{"result":"not-run"}' ]
}

@test "new latest version invalidates carried latest cells" {
  NJ_ARG='{"published_at":"x","sha":"aaaa1111","note":"n"}' PS="$F/prev-status.json" PH="$F/prev-history.json" LATEST=0.8.28 run_bs
  [ "$(st '.projects[]|select(.name=="ebb").cells.latest')" = '{"result":"not-run"}' ]
  [ "$(st '.projects[]|select(.name=="glitter").cells.latest')" = '{"result":"not-run"}' ]
  # nightly unaffected
  [ "$(st '.projects[]|select(.name=="glitter").cells.nightly.result')" = '"pass"' ]
}

@test "new nightly sha invalidates carried nightly cells" {
  PS="$F/prev-status.json" PH="$F/prev-history.json" run_bs
  [ "$(st '.projects[]|select(.name=="glitter").cells.nightly.result')" = '"not-run"' ]
  [ "$(st '.projects[]|select(.name=="glitter").cells.latest.result')" = '"pass"' ]
}

@test "same nightly sha keeps carried nightly cells" {
  NJ_ARG='{"published_at":"x","sha":"aaaa1111","note":"n"}' PS="$F/prev-status.json" run_bs
  [ "$(st '.projects[]|select(.name=="glitter").cells.nightly.result')" = '"pass"' ]
}

@test "null nightly-json keeps previous nightly cells and nightly is null" {
  NJ_ARG=null PS="$F/prev-status.json" run_bs
  [ "$status" -eq 0 ]
  [ "$(st '.jolt.nightly')" = null ]
  [ "$(st '.projects[]|select(.name=="glitter").cells.nightly.result')" = '"pass"' ]
}

@test "pin history appended only when run_url is new" {
  PS="$F/prev-status.json" PH="$F/prev-history.json" run_bs
  # glitter's own-pin run_url equals the newest pin history entry
  [ "$(hi '.projects.glitter.pin|length')" = 1 ]
  # ebb has no pin history yet
  [ "$(hi '.projects.ebb.pin|length')" = 1 ]
  # second tick over the first one's output adds nothing
  cp "$OUT/history.json" "$BATS_TEST_TMPDIR/h2.json"
  PS="$OUT/status.json" PH="$BATS_TEST_TMPDIR/h2.json" run_bs
  [ "$(hi '.projects.ebb.pin|length')" = 1 ]
  [ "$(hi '.projects["raygui-jlt"].pin|length')" = 1 ]
}

@test "pin history grows when the pin run changes" {
  PS="$F/prev-status.json" PH="$F/prev-history.json" run_bs
  jq '.[0].run_url="https://github.com/jlt-commons/glitter/actions/runs/99"' "$F/own-pin.json" > "$BATS_TEST_TMPDIR/op.json"
  cp "$OUT/history.json" "$BATS_TEST_TMPDIR/h2.json"
  OP="$BATS_TEST_TMPDIR/op.json" PH="$BATS_TEST_TMPDIR/h2.json" run_bs
  [ "$(hi '.projects.glitter.pin|length')" = 2 ]
  [ "$(hi '.projects.glitter.pin[0].run_url')" = '"https://github.com/jlt-commons/glitter/actions/runs/99"' ]
}

@test "history capped at 30, newest first" {
  jq '.projects.glitter.latest = [range(0;30) | {result:"pass",version:"0.8.26",installed:null,run_url:("old/"+tostring),finished_at:null,failed_step:null}]' \
    "$F/prev-history.json" > "$BATS_TEST_TMPDIR/h.json"
  PH="$BATS_TEST_TMPDIR/h.json" run_bs "$F/results-latest.json"
  [ "$(hi '.projects.glitter.latest|length')" = 30 ]
  [ "$(hi '.projects.glitter.latest[0].run_url')" = '"https://github.com/jlt-commons/glitter/actions/runs/11"' ]
  [ "$(hi '.projects.glitter.latest[29].run_url')" = '"old/28"' ]
}

@test "failing first, then by name" {
  # raygui-jlt has a failing own-pin cell
  run_bs
  [ "$(st '.projects|map(.name)')" = '["raygui-jlt","ebb","glitter"]' ]
}

@test "mismatch cell sorts before passing ones" {
  jq '.[2].result="pass"' "$F/own-pin.json" > "$BATS_TEST_TMPDIR/op.json"
  OP="$BATS_TEST_TMPDIR/op.json" run_bs "$F/results-nightly.json"
  [ "$(st '.projects|map(.name)')" = '["raygui-jlt","ebb","glitter"]' ]
}

@test "malformed results: non-zero exit and no files written" {
  echo '{"requested":"0.8.27","projects":"nope"}' > "$BATS_TEST_TMPDIR/bad.json"
  run_bs "$BATS_TEST_TMPDIR/bad.json"
  [ "$status" -ne 0 ]
  [ ! -e "$OUT/status.json" ]
  [ ! -e "$OUT/history.json" ]
}

@test "unknown result value is rejected" {
  jq '.projects[0].result="weird"' "$F/results-latest.json" > "$BATS_TEST_TMPDIR/bad.json"
  run_bs "$BATS_TEST_TMPDIR/bad.json"
  [ "$status" -ne 0 ]
  [ ! -e "$OUT/status.json" ]
}

@test "unparseable results file: non-zero exit" {
  echo '{not json' > "$BATS_TEST_TMPDIR/bad.json"
  run_bs "$BATS_TEST_TMPDIR/bad.json"
  [ "$status" -ne 0 ]
  [ ! -e "$OUT/status.json" ]
}
