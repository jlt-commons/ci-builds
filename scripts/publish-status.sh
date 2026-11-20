#!/usr/bin/env bash
# Commit status.json and history.json to the orphan `status` branch of origin.
#
# usage: REBUILD='<shell command>' publish-status.sh <out-dir>
#
# Run inside a checkout whose origin it can push to. REBUILD runs on every
# attempt, the first included, with PREV_DIR (the freshly fetched status-branch
# files, which may be absent) and OUT_DIR (<out-dir>) in its environment, and
# must write OUT_DIR/status.json and OUT_DIR/history.json. Per attempt: fetch,
# worktree at the branch tip, REBUILD, compare, commit, push. A rejected push
# means someone else published meanwhile, so the next attempt rebuilds on top
# of their result. Three attempts, then non-zero.
#
# "Unchanged" ignores generated_at and canary_run in status.json (both change
# every tick) and is byte-exact for history.json.
set -euo pipefail

[ $# -eq 1 ] || { echo "usage: REBUILD=... publish-status.sh <out-dir>" >&2; exit 2; }
[ -n "${REBUILD:-}" ] || { echo "publish-status: REBUILD is required" >&2; exit 2; }
out=$1
mkdir -p "$out"
out=$(cd "$out" && pwd)

tmp=$(mktemp -d)
wt="$tmp/wt"
trap 'git worktree remove --force "$wt" 2>/dev/null || true; git worktree prune; rm -rf "$tmp"' EXIT

norm() { jq -S 'del(.generated_at, .canary_run)' "$1"; }
bot=(-c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com")

for attempt in 1 2 3; do
  rm -rf "$tmp/prev"; mkdir -p "$tmp/prev"
  git worktree remove --force "$wt" 2>/dev/null || true
  rm -rf "$wt"

  rc=0
  git ls-remote --exit-code --heads origin status >/dev/null 2>&1 || rc=$?
  case "$rc" in
    0)
      git fetch --quiet origin +refs/heads/status:refs/remotes/origin/status
      git worktree add --quiet --detach "$wt" refs/remotes/origin/status
      for f in status.json history.json; do
        [ -f "$wt/$f" ] && cp "$wt/$f" "$tmp/prev/$f"
      done
      ;;
    2)
      git worktree add --quiet --detach "$wt" HEAD
      git -C "$wt" checkout --quiet --orphan status
      git -C "$wt" rm -rfq .
      ;;
    *) echo "publish-status: cannot reach origin (ls-remote exit $rc)" >&2; exit 1 ;;
  esac

  PREV_DIR="$tmp/prev" OUT_DIR="$out" sh -c "$REBUILD"
  [ -f "$out/status.json" ] && [ -f "$out/history.json" ] \
    || { echo "publish-status: REBUILD did not write both files in $out" >&2; exit 1; }

  if [ -f "$wt/status.json" ] && [ -f "$wt/history.json" ] \
     && cmp -s "$out/history.json" "$wt/history.json" \
     && [ "$(norm "$out/status.json")" = "$(norm "$wt/status.json")" ]; then
    echo "status: unchanged"
    exit 0
  fi

  cp "$out/status.json" "$out/history.json" "$wt/"
  git -C "$wt" add status.json history.json
  git -C "$wt" "${bot[@]}" commit --quiet -m "status: update fleet status"
  if git -C "$wt" push --quiet origin HEAD:refs/heads/status; then
    echo "status: published (attempt $attempt)"
    exit 0
  fi
  echo "publish-status: push rejected on attempt $attempt, retrying" >&2
done
echo "publish-status: gave up after 3 rejected pushes" >&2
exit 1
