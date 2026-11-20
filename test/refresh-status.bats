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

@test "two publishes whose only differences are the clock and canary_run make one commit" {
  T="$BATS_TEST_TMPDIR/pub"; mkdir -p "$T"
  git init -q --bare "$T/origin.git"
  git init -q -b main "$T/work"
  git -C "$T/work" config user.email t@example.com; git -C "$T/work" config user.name t
  git -C "$T/work" remote add origin "$T/origin.git"
  echo x > "$T/work/README"; git -C "$T/work" add README; git -C "$T/work" commit -q -m init
  git -C "$T/work" push -q origin main
  export LATEST=0.8.27
  # The stable note the canary plan job now emits: no age in it.
  export NIGHTLY_JSON='{"published_at":"2026-11-20T01:00:00Z","sha":"f4e82fc8eac874cae1658b7976518253027c4f6a","note":"nightly published 2026-11-20T01:00:00Z at f4e82fc8"}'
  export REBUILD="bash $S/scripts/refresh-status.sh \"\$OUT_DIR\" $F/results-latest.json"
  cd "$T/work"
  CANARY_RUN=https://example/run/1 bash "$S/scripts/publish-status.sh" "$T/out1"
  sleep 1
  CANARY_RUN=https://example/run/2 run bash "$S/scripts/publish-status.sh" "$T/out2"
  [ "$status" -eq 0 ]
  [[ "$output" == *"status: unchanged"* ]]
  [ "$(git -C "$T/origin.git" rev-list --count status)" -eq 1 ]
}
