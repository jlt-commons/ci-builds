setup() {
  ROOT="$BATS_TEST_DIRNAME/.."
  cat > "$BATS_TMPDIR/gh-stub" <<'S'
#!/usr/bin/env bash
# Serves canned files from test/fixtures/gh by argument pattern; honours --jq.
D="$FIXTURE_DIR"; a=" $* "; jqf=""; prev=""
for x in "$@"; do [ "$prev" = "--jq" ] && jqf=$x; prev=$x; done
out() { if [ -n "$jqf" ]; then jq -r "$jqf" "$1"; else cat "$1"; fi; }
fail() { echo "gh: stub has no fixture for:$a (HTTP 404)" >&2; exit 1; }
case "$a" in
  *"orgs/jlt-commons/repos"*) out "$D/org-repos.json" ;;
  *"repos/jlt-commons/ratelimited/contents/"*) echo "API rate limit exceeded for installation ID 4040404 (HTTP 403)" >&2; exit 1 ;;
  *"repos/jlt-commons/brokenrepo/contents/"*) echo "HTTP 500" >&2; exit 1 ;;
  *"repos/jlt-commons/"*"/contents/.github/workflows/ci.yml"*)
    p=${a#*repos/jlt-commons/}; p=${p%%/*}; [ -f "$D/$p.ci.yml" ] || fail; cat "$D/$p.ci.yml" ;;
  "  api repos/jlt-commons/"*" --jq .default_branch "|*"api repos/jlt-commons/"*"--jq .default_branch"*)
    p=${a#*repos/jlt-commons/}; p=${p%% *}; [ -f "$D/$p.branch" ] || fail; cat "$D/$p.branch" ;;
  *"run list --repo jlt-commons/"*)
    p=${a#*--repo jlt-commons/}; p=${p%% *}; [ -f "$D/$p.runs.json" ] || fail
    case "$a" in *"--branch trunk"*) [ "$p" = glitter-gl ] || fail ;; esac
    out "$D/$p.runs.json" ;;
  *"run view "*"--repo jlt-commons/"*)
    p=${a#*--repo jlt-commons/}; p=${p%% *}; [ -f "$D/$p.jobs.json" ] || fail; out "$D/$p.jobs.json" ;;
  *) fail ;;
esac
S
  chmod +x "$BATS_TMPDIR/gh-stub"
  export GH="$BATS_TMPDIR/gh-stub" FIXTURE_DIR="$ROOT/test/fixtures/gh"
  cat > "$BATS_TMPDIR/fleet.json" <<'F'
{"projects":["glitter","glitter-gl","glitter-uikit","ghost"],"excluded":{"jolt-lsp":"releases jolt-lsp","secretrepo":"private"}}
F
  export FLEET_JSON="$BATS_TMPDIR/fleet.json"
}

own() { bash "$ROOT/scripts/collect-own-pin.sh" 2>"$BATS_TMPDIR/err"; }
unc() { bash "$ROOT/scripts/collect-uncovered.sh" 2>"$BATS_TMPDIR/err"; }

@test "own-pin: one entry per fleet project" {
  run own; [ "$status" -eq 0 ]
  [ "$(jq length <<<"$output")" -eq 4 ]
  [ "$(jq -r '[.[].name]|join(",")' <<<"$output")" = "glitter,glitter-gl,glitter-uikit,ghost" ]
}
@test "own-pin: pass run with literal pin" {
  run own
  jq -e '.[0] | .pin==null and .result=="pass" and .run_url=="https://github.com/jlt-commons/glitter/actions/runs/1" and .finished_at=="2026-11-19T10:00:00Z" and .failed_step==null' <<<"$output"
}
@test "own-pin: default branch is used, failed run names the failed step, pin parsed" {
  run own
  jq -e '.[1] | .pin=="0.8.24" and .result=="fail" and .failed_step=="Run tests"' <<<"$output"
}
@test "own-pin: no push run yet is not-run, non-literal pin is null" {
  run own
  jq -e '.[2] | .result=="not-run" and .pin==null and .run_url==null and .finished_at==null' <<<"$output"
}
@test "own-pin: a repo whose API fails is not-run, warns, and does not abort" {
  run own
  jq -e '.[3] | .name=="ghost" and .result=="not-run" and .pin==null' <<<"$output"
  grep -q 'ghost' "$BATS_TMPDIR/err"
}
@test "own-pin: conclusion mapping" {
  for c in success:pass failure:fail timed_out:timed-out cancelled:not-run skipped:not-run neutral:not-run null:not-run; do
    echo "[{\"conclusion\":${c%%:*},\"url\":\"u\",\"updatedAt\":\"t\",\"databaseId\":1}]" | sed 's/:\([a-z_]*\),/:"\1",/; s/:"null"/:null/' > "$BATS_TMPDIR/runs.json"
    cp "$BATS_TMPDIR/runs.json" "$FIXTURE_DIR/zz.runs.json"; echo main > "$FIXTURE_DIR/zz.branch"
    echo '{"projects":["zz"],"excluded":{}}' > "$FLEET_JSON"
    run own; [ "$(jq -r '.[0].result' <<<"$output")" = "${c##*:}" ] || { echo "$c -> $output"; rm -f "$FIXTURE_DIR"/zz.*; return 1; }
    rm -f "$FIXTURE_DIR"/zz.*
  done
}
@test "own-pin: GH may be several words" {
  export GH="env X=1 $BATS_TMPDIR/gh-stub"
  run own; [ "$(jq length <<<"$output")" -eq 4 ]
}

@test "uncovered: reasons per repo, fleet members and archived/private skipped" {
  run unc; [ "$status" -eq 0 ]
  [ "$(jq -r '.[]|select(.name=="jolt-lsp").reason' <<<"$output")" = "excluded: releases jolt-lsp" ]
  [ "$(jq -r '.[]|select(.name=="nociyml").reason' <<<"$output")" = "no ci.yml" ]
  [ "$(jq -r '.[]|select(.name=="noinput").reason' <<<"$output")" = "ci.yml has no jolt-version input" ]
  [ "$(jq -r '.[]|select(.name=="readyrepo").reason' <<<"$output")" = "ready, not yet in fleet.json" ]
  [ "$(jq '[.[]|select(.name=="glitter")]|length' <<<"$output")" -eq 0 ]
  [ "$(jq '[.[]|select(.name=="oldrepo" or .name=="secretrepo")]|length' <<<"$output")" -eq 0 ]
}
@test "uncovered: a repo that cannot be read gets a reason and a warning" {
  run unc
  [ "$(jq -r '.[]|select(.name=="brokenrepo").reason' <<<"$output")" = "could not read repository" ]
  grep -q brokenrepo "$BATS_TMPDIR/err"
}
@test "uncovered: on key parsed as boolean true still finds the input" {
  echo '{"projects":[],"excluded":{}}' > "$FLEET_JSON"
  run unc
  [ "$(jq -r '.[]|select(.name=="glitter").reason' <<<"$output")" = "ready, not yet in fleet.json" ]
}
@test "uncovered: a 403 whose text contains 404 is not treated as a missing ci.yml" {
  run unc
  [ "$(jq -r '.[]|select(.name=="ratelimited").reason' <<<"$output")" = "could not read repository" ]
  grep -q ratelimited "$BATS_TMPDIR/err"
}
@test "uncovered: unparsable ci.yml is could not read repository, with a warning" {
  run unc
  [ "$(jq -r '.[]|select(.name=="badyaml").reason' <<<"$output")" = "could not read repository" ]
  grep -q badyaml "$BATS_TMPDIR/err"
}
@test "uncovered: missing yq fails loudly" {
  mkdir -p "$BATS_TMPDIR/nobin"; for t in bash jq mktemp rm dirname cat grep tail env; do ln -sf "$(command -v $t)" "$BATS_TMPDIR/nobin/$t"; done
  PATH="$BATS_TMPDIR/nobin" run bash "$ROOT/scripts/collect-uncovered.sh"
  [ "$status" -ne 0 ]; [[ "$output" == *yq* ]]
}
