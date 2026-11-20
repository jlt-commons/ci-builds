setup() {
  ROOT="$BATS_TEST_DIRNAME/.."
  T=$(mktemp -d "$BATS_TMPDIR/pub.XXXXXX")
  git init -q --bare "$T/origin.git"
  git init -q -b main "$T/work"
  git -C "$T/work" config user.email t@example.com
  git -C "$T/work" config user.name t
  git -C "$T/work" remote add origin "$T/origin.git"
  echo x > "$T/work/README"
  git -C "$T/work" add README
  git -C "$T/work" commit -q -m init
  git -C "$T/work" push -q origin main
  export T
  # Default REBUILD: write fixed files regardless of PREV_DIR.
  cat > "$T/rebuild-fixed.sh" <<'R'
#!/usr/bin/env bash
mkdir -p "$OUT_DIR"
echo "{\"schema\":1,\"generated_at\":\"$(date +%s%N)\",\"canary_run\":\"run-$(date +%s%N)\",\"projects\":[\"a\"]}" > "$OUT_DIR/status.json"
echo '{"schema":1,"adhoc":[]}' > "$OUT_DIR/history.json"
R
  chmod +x "$T/rebuild-fixed.sh"
}

pub() { (cd "$T/work" && REBUILD="$1" bash "$ROOT/scripts/publish-status.sh" "$T/out"); }
remote_show() { git -C "$T/origin.git" show "status:$1"; }

@test "creates the branch on first publish" {
  run pub "$T/rebuild-fixed.sh"
  [ "$status" -eq 0 ]
  [ "$(git -C "$T/origin.git" rev-list --count status)" -eq 1 ]
  [ "$(remote_show status.json | jq '.projects|length')" -eq 1 ]
  [ "$(remote_show history.json | jq .schema)" -eq 1 ]
  # orphan: no README from main
  run git -C "$T/origin.git" show status:README
  [ "$status" -ne 0 ]
  [ "$(git -C "$T/origin.git" log -1 --format=%an status)" = "github-actions[bot]" ]
}

@test "no commit when only generated_at and canary_run changed" {
  pub "$T/rebuild-fixed.sh"
  before=$(git -C "$T/origin.git" rev-parse status)
  run pub "$T/rebuild-fixed.sh"
  [ "$status" -eq 0 ]
  [[ "$output" == *"status: unchanged"* ]]
  [ "$(git -C "$T/origin.git" rev-parse status)" = "$before" ]
}

@test "commits when history.json changes" {
  pub "$T/rebuild-fixed.sh"
  before=$(git -C "$T/origin.git" rev-parse status)
  cat > "$T/rebuild-hist.sh" <<'R'
#!/usr/bin/env bash
echo '{"schema":1,"projects":["a"]}' > "$OUT_DIR/status.json"
echo '{"schema":1,"adhoc":[1]}' > "$OUT_DIR/history.json"
R
  run pub "bash $T/rebuild-hist.sh"
  [ "$status" -eq 0 ]
  [ "$(git -C "$T/origin.git" rev-parse status)" != "$before" ]
  [ "$(remote_show history.json | jq '.adhoc|length')" -eq 1 ]
}

@test "retries after a concurrent push and keeps both results" {
  pub "$T/rebuild-fixed.sh"
  # REBUILD adds its own project to whatever PREV_DIR holds. On the first
  # attempt it also pushes a competing commit (project "theirs") after the
  # fetch, so our push is rejected and attempt 2 must rebuild on top of it.
  cat > "$T/rebuild-merge.sh" <<'R'
#!/usr/bin/env bash
set -e
n=$(cat "$T/attempts" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$T/attempts"
if [ "$n" -eq 1 ]; then
  c=$(mktemp -d "$T/rival.XXXXXX")
  git clone -q --branch status "$T/origin.git" "$c"
  jq '.projects += ["theirs"]' "$c/status.json" > "$c/s" && mv "$c/s" "$c/status.json"
  git -C "$c" -c user.email=r@example.com -c user.name=rival commit -q -am rival
  git -C "$c" push -q origin status
fi
mkdir -p "$OUT_DIR"
jq '.projects += ["ours"]' "$PREV_DIR/status.json" > "$OUT_DIR/status.json"
cp "$PREV_DIR/history.json" "$OUT_DIR/history.json"
R
  run pub "bash $T/rebuild-merge.sh"
  [ "$status" -eq 0 ]
  [ "$(cat "$T/attempts")" -eq 2 ]
  [ "$(remote_show status.json | jq -c '.projects|sort')" = '["a","ours","theirs"]' ]
}

@test "gives up after 3 rejections with non-zero exit" {
  pub "$T/rebuild-fixed.sh"
  cat > "$T/rebuild-lose.sh" <<'R'
#!/usr/bin/env bash
set -e
n=$(cat "$T/attempts" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$T/attempts"
c=$(mktemp -d "$T/rival.XXXXXX")
git clone -q --branch status "$T/origin.git" "$c"
echo "{\"schema\":1,\"projects\":[\"rival$n\"]}" > "$c/status.json"
git -C "$c" -c user.email=r@example.com -c user.name=rival commit -q -am "rival $n"
git -C "$c" push -q origin status
mkdir -p "$OUT_DIR"
echo "{\"schema\":1,\"projects\":[\"ours$n\"]}" > "$OUT_DIR/status.json"
echo '{"schema":1}' > "$OUT_DIR/history.json"
R
  run pub "bash $T/rebuild-lose.sh"
  [ "$status" -ne 0 ]
  [ "$(cat "$T/attempts")" -eq 3 ]
}

@test "fails without REBUILD" {
  run bash -c "cd '$T/work' && env -u REBUILD bash '$ROOT/scripts/publish-status.sh' '$T/out'"
  [ "$status" -ne 0 ]
  [[ "$output" == *"REBUILD"* ]]
}
