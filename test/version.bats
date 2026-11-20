setup() { source "$BATS_TEST_DIRNAME/../scripts/lib/version.sh"; F="$BATS_TEST_DIRNAME/fixtures"; }

@test "release log"        { run installed_version "$F/log-release.txt"; [ "$output" = "0.8.24" ]; }
@test "nightly log"        { run installed_version "$F/log-nightly.txt"; [ "$output" = "0.8.27-241-gdd3c4a4b" ]; }
@test "no version line"    { printf 'Successfully installed jolt vnightly to /x\n' > "$BATS_TMPDIR/l"; run installed_version "$BATS_TMPDIR/l"; [ "$status" -eq 1 ]; [ -z "$output" ]; }
@test "v prefix requested" { run version_matches v0.8.24 0.8.24; [ "$status" -eq 0 ]; }
@test "release mismatch"   { run version_matches 0.8.27 0.8.24; [ "$status" -eq 1 ]; }
@test "nightly sha match"  { run version_matches nightly 0.8.27-241-gdd3c4a4b dd3c4a4b0123456789abcdef0123456789abcdef; [ "$status" -eq 0 ]; }
@test "nightly stale sha"  { run version_matches nightly 0.8.27-241-gdd3c4a4b 98e5ef990000000000000000000000000000000a; [ "$status" -eq 1 ]; }
@test "nightly no sha"     { run version_matches nightly 0.8.27-241-gdd3c4a4b; [ "$status" -eq 1 ]; }
@test "nightly got a release" { run version_matches nightly 0.8.27 dd3c4a4b00; [ "$status" -eq 1 ]; }

pin() { printf '%s\n' "$1" > "$BATS_TMPDIR/ci.yml"; run parse_pin "$BATS_TMPDIR/ci.yml"; }

@test "pin: input with default"   { pin "  JOLT_VERSION: \${{ inputs.jolt-version || '0.8.24' }}"; [ "$status" -eq 0 ]; [ "$output" = "0.8.24" ]; }
@test "pin: input with v default" { pin "  JOLT_VERSION: \${{ inputs.jolt-version || 'v0.8.24' }}"; [ "$status" -eq 0 ]; [ "$output" = "0.8.24" ]; }
@test "pin: quoted literal"       { pin '  JOLT_VERSION: "0.8.24"'; [ "$status" -eq 0 ]; [ "$output" = "0.8.24" ]; }
@test "pin: needs output is not literal" { pin "  JOLT_VERSION: \${{ needs.jolt-version.outputs.version }}"; [ "$status" -eq 1 ]; [ -z "$output" ]; }
@test "pin: comment mentions are skipped" {
  printf '# JOLT_VERSION: 9.9.9 is old\nenv:\n  JOLT_VERSION: "0.8.24"\n' > "$BATS_TMPDIR/ci.yml"
  run parse_pin "$BATS_TMPDIR/ci.yml"; [ "$status" -eq 0 ]; [ "$output" = "0.8.24" ]
}
@test "pin: no key at all" { printf 'name: x\n' > "$BATS_TMPDIR/ci.yml"; run parse_pin "$BATS_TMPDIR/ci.yml"; [ "$status" -eq 1 ]; [ -z "$output" ]; }
@test "pin: missing file"  { run parse_pin "$BATS_TMPDIR/nope.yml"; [ "$status" -eq 1 ]; }

@test "uncovered: in fleet prints nothing" { run uncovered_reason glitter 1 1 1 ""; [ "$status" -eq 0 ]; [ -z "$output" ]; }
@test "uncovered: in fleet beats excluded" { run uncovered_reason glitter 0 0 1 "why"; [ -z "$output" ]; }
@test "uncovered: excluded"   { run uncovered_reason jolt-lsp 1 0 0 "needs input"; [ "$output" = "excluded: needs input" ]; }
@test "uncovered: excluded beats no ci" { run uncovered_reason x 0 0 0 "private demo"; [ "$output" = "excluded: private demo" ]; }
@test "uncovered: no ci.yml"  { run uncovered_reason x 0 0 0 ""; [ "$output" = "no ci.yml" ]; }
@test "uncovered: no input"   { run uncovered_reason x 1 0 0 ""; [ "$output" = "ci.yml has no jolt-version input" ]; }
@test "uncovered: ready"      { run uncovered_reason x 1 1 0 ""; [ "$output" = "ready, not yet in fleet.json" ]; }
