#!/usr/bin/env bash
# Pure helpers for the fleet dashboard. Sourced, so no `set -e` here.

# installed_version <logfile>
# Prints the version from the first "jolt v<semver>[-N-g<hex>]" line (after the
# Actions timestamp), without the leading v. Exits 1 when there is none.
installed_version() {
  local line text re='^jolt v([0-9]+\.[0-9]+\.[0-9]+(-[0-9]+-g[0-9a-f]+)?)$'
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    text=$(printf '%s' "$line" | sed -E 's/^[0-9T:.-]+Z //')
    if [[ $text =~ $re ]]; then
      printf '%s\n' "${BASH_REMATCH[1]}"
      return 0
    fi
  done < "$1"
  return 1
}

# version_matches <requested> <installed> [nightly_sha]
version_matches() {
  local requested=$1 installed=$2 sha=${3:-} re='-g([0-9a-f]+)$'
  if [ "$requested" = "nightly" ]; then
    [ -n "$sha" ] || return 1
    [[ $installed =~ $re ]] || return 1
    [[ $sha == "${BASH_REMATCH[1]}"* ]]
    return
  fi
  [ "${requested#v}" = "$installed" ]
}

# parse_pin <ci.yml>
# Prints the literal version from the first JOLT_VERSION: key line, without v.
# Exits 1 when the file or key is missing or the pin is not a literal version.
parse_pin() {
  local line re
  re="['\"]v?([0-9]+\\.[0-9]+\\.[0-9]+)['\"]"
  [ -r "$1" ] || return 1
  line=$(grep -m1 -E '^[[:space:]]*JOLT_VERSION:' "$1") || return 1
  [[ $line =~ $re ]] || return 1
  printf '%s\n' "${BASH_REMATCH[1]}"
}

# uncovered_reason <name> <has_ci> <has_input> <in_fleet> <excluded_reason>
uncovered_reason() {
  local has_ci=$2 has_input=$3 in_fleet=$4 excluded=${5:-}
  [ "$in_fleet" = "1" ] && return 0
  if [ -n "$excluded" ]; then
    printf 'excluded: %s\n' "$excluded"
  elif [ "$has_ci" = "0" ]; then
    printf 'no ci.yml\n'
  elif [ "$has_input" = "0" ]; then
    printf 'ci.yml has no jolt-version input\n'
  else
    printf 'ready, not yet in fleet.json\n'
  fi
}
