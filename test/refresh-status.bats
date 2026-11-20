setup() {
  ROOT="$BATS_TEST_DIRNAME/.."
  F="$ROOT/test/fixtures/status"
  # Copy the scripts so the network collectors can be replaced by stubs that
  # print fixtures; refresh-status.sh finds its siblings relative to itself.
  S="$BATS_TEST_TMPDIR/ci"
  mkdir -p "$S"
  cp -R "$ROOT/scripts" "$S/scripts"
  printf '#!/usr/bin/env bash\ncat %q\n' "$F/own-pin.json" > "$S/scripts/collect-own-pin.sh"
  printf '#!/usr/bin/env bash\ncat %q\n' "$F/uncovered.json" > "$S/scripts/collect-uncovered.sh"
}

@test "refresh-status writes status.json and history.json from collectors and results" {
  export LATEST=0.8.27 NIGHTLY_JSON=null CANARY_RUN=https://example/run/1
  PREV_DIR="$BATS_TEST_TMPDIR/none" run bash "$S/scripts/refresh-status.sh" "$BATS_TEST_TMPDIR/out" "$F/results-latest.json"
  [ "$status" -eq 0 ]
  [ "$(jq .schema "$BATS_TEST_TMPDIR/out/status.json")" -eq 1 ]
  [ "$(jq -c '.projects|map(.name)|sort' "$BATS_TEST_TMPDIR/out/status.json")" = '["ebb","glitter","raygui-jlt"]' ]
  [ "$(jq .schema "$BATS_TEST_TMPDIR/out/history.json")" -eq 1 ]
}

@test "refresh-status needs LATEST" {
  run env -u LATEST NIGHTLY_JSON=null CANARY_RUN=x bash "$S/scripts/refresh-status.sh" "$BATS_TEST_TMPDIR/out"
  [ "$status" -ne 0 ]
  [[ "$output" == *"LATEST"* ]]
}
