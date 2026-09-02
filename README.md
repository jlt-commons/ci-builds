# ci-builds

Fleet CI for [jlt-commons](https://github.com/jlt-commons): build every project
against one jolt version and report the result in a single table.

## Why

Each project pins the jolt it builds against. When a new jolt ships, the
question worth answering is not "does my project still build" but "does
anything in the fleet break", and answering it one repository at a time is how
you find out days late.

jolt v0.8.0 is the case that prompted this. It published on 2026-09-01 and
reversed `jolt.ffi/write`'s last two arguments
([jolt-lang/jolt#802](https://github.com/jolt-lang/jolt/pull/802)). Both
spellings are plain integers, so an older or newer runtime than the code
expects writes to the wrong address and reports nothing. Two projects were
floating to `latest` at the time and silently moved onto it.

## What it does

`fan-out` does not build anything itself. Every project's own `ci.yml` already
knows how to build it, and each accepts a `jolt-version` dispatch input that
overrides its pinned `JOLT_VERSION` for a single run. So `fan-out` dispatches
those five workflows, waits, and renders the answers:

```
## jolt `0.8.0` across jlt-commons

| project         | result | run    |
|-----------------|--------|--------|
| `glitter`       | pass   | [run]  |
| `glitter-gl`    | pass   | [run]  |
| `glitter-uikit` | pass   | [run]  |
| `raygui-jlt`    | pass   | [run]  |
| `raylib-jlt`    | pass   | [run]  |

All 5 projects build against jolt `0.8.0`.
```

Reusing each project's real gate matters. `raygui-jlt` needs raylib natives,
`glitter-uikit` needs AppKit and GTK4, `glitter-gl` needs a GL context under
Xvfb. A central rebuild of all that would rot, and would test something other
than what a contributor hits.

`canary` runs `fan-out` against the jolt versions this fleet cares about, opens
an issue when they break and closes it when a later run passes, so an open
canary issue means "broken right now" rather than "broke once". Two versions:

| | |
|---|---|
| pinned | what the projects pin today, `vars.JOLT_PIN`, default `0.8.1` |
| latest | jolt's newest published release |

They are the same while the fleet is current, and the run collapses to one
build. They diverge the moment jolt ships a release nobody has adopted, which
is the window worth watching.

It ticks hourly and two gates decide whether a tick does anything:

- **interval** — `vars.CANARY_INTERVAL_HOURS`, default `1`. A cron cannot be a
  variable, GitHub requires a literal, so the schedule ticks hourly and this
  decides how often a tick is allowed to do work. Measured from the last run
  that actually built something, since a gated tick still finishes as a
  successful run and would otherwise reset the clock every hour.
- **change** — a version set that already passed is not retested. Held in the
  Actions cache, so there is nothing to commit back and no extra permission.

A manual dispatch ignores both.

**Both rows are releases, and jolt's `main` is testable too.** This section
used to say testing an unreleased jolt would mean building it from source
against Chez on two operating systems. That stopped being true on 2026-09-02,
when [jolt-lang/jolt#824](https://github.com/jolt-lang/jolt/pull/824) merged and
jolt began publishing a rolling `vnightly` prerelease from main. Every project
already takes a `jolt-version` input, so:

```
gh workflow run fanout.yml --repo jlt-commons/ci-builds -f jolt-version=nightly
```

is the whole thing, no code anywhere. Run [33691047983](https://github.com/jlt-commons/ci-builds/actions/runs/33691047983)
did exactly that and all five passed.

`nightly` is deliberately not in the canary matrix yet, and cost is not the
reason. jolt publishes the nightly by staging a draft, deleting the old release,
then moving the tag, and only after its own downstream-library gates pass. So a
lagging library withholds the new artifact while the previous `vnightly` stays
up and installs perfectly. A green `nightly` row would not distinguish "main is
healthy" from "main broke on Tuesday and we are still testing Tuesday's binary".
Adding the row needs a freshness check on the release's `published_at` first.
A release row has none of this problem, because a published release is
immutable.

## Using it

**Try a candidate jolt across everything.** Actions → fan-out → Run workflow,
and give it a version (blank means jolt's latest release). Nothing is
committed anywhere; each project builds at its own HEAD with the version you
named.

**Try one project.** Dispatch that project's own `ci.yml` with the
`jolt-version` input. Same mechanism, one repository.

**Adopt a new jolt.** Run `fan-out` against it, then, when green, move each
project's `JOLT_VERSION` pin in one reviewed commit per repository. Moving a
pin IS the cutover commit for a breaking jolt change, which is the point of
pinning rather than floating.

## The pin convention

Every project carries a workflow-level pin:

```yaml
env:
  JOLT_VERSION: ${{ inputs.jolt-version || '0.8.1' }}
```

A floating `latest` lets a release published overnight turn CI red on a tree
nobody touched, which is the same reason clj-kondo, clojure-lsp and babashka
are pinned in those workflows.

Note what `:jolt/min-version` in a project's `deps.edn` does **not** do. jolt
honours that key only from
[#804](https://github.com/jolt-lang/jolt/pull/804), which is the direct child
of #802, so every runtime old enough to have the old `ffi/write` order is also
too old to read the key and ignores it. Measured against the released v0.7.29
with a `0.8.0` floor present: it ran rather than refusing, dropped two test
namespaces, and still reported zero failures on half the suite. The pin here is
what actually selects the runtime; the floor is for the next break, and to
state the requirement for consumers. See
[jolt-lang/jolt#811](https://github.com/jolt-lang/jolt/issues/811).

## Authentication

Runs authenticate as the **jlt-commons-ci** GitHub App, not a personal access
token. The App is owned by the organization, mints a token that lives one hour
and reaches exactly six repositories, and is what lets someone trigger a fleet
build without borrowing another person's identity. A PAT would attribute every
dispatched run to whoever created it and would die with their access.

`APP_ID` and `APP_PRIVATE_KEY` are repository secrets here. Repository rather
than organization secrets because this is a free organization, where org
secrets cover public repositories only, and because the credential's blast
radius should be the one repository that needs it.

## Setting the App up again

`scripts/setup-app.sh` walks the whole thing: create the App, set its
permissions, install it, store the credentials in `pass`, create this
repository, and set its secrets. It is the script that actually created the
current setup, corrected afterwards for two things it got wrong the first time.

```
bash scripts/setup-app.sh
```

It is idempotent enough to re-run: stage 1 asks whether the App already exists
and skips creation if so, and stage 5 detects this repository and moves on. You
would need it to rotate the private key, to rebuild the App after an uninstall,
or to set the same thing up for another organization.

Two things it records that are easy to get wrong:

- The workflows authenticate with the App's **Client ID**, not its App ID.
  `create-github-app-token@v3` deprecates the `app-id` input, so a setup that
  only captures the App ID produces credentials the workflows cannot use.
- `pass` **auto-commits** every entry it writes but never pushes. The
  credentials are not backed up until `git -C ~/dev/b12n-pass push`.

## Adding a project

1. Give it a `workflow_dispatch` input named `jolt-version` and a
   workflow-level `JOLT_VERSION` pin that reads it.
2. Add the repository to the jlt-commons-ci App installation.
3. Add its name to `PROJECTS` in `.github/workflows/fanout.yml`.

## Known gaps

- **The canary polls rather than being told.** A `repository_dispatch` from
  `jolt-lang/jolt` on release would be the better signal, but jolt is a
  different organization where we are read-only, so that change is not ours to
  make. Worth asking about.
- **`glitter-gl` has a shim a single pin cannot fully exercise.**
  `glitter-gl.ffi-compat` resolves `ffi/write`'s argument order at load and has
  two branches; a run pinned to one jolt only ever takes one of them. A
  two-entry matrix over an old and a new jolt would cover both.
- **`PROJECTS` is a hardcoded list**, so a new repository is invisible until
  someone edits the workflow. Deliberate for now: discovery by topic or by
  listing the App's installation would be quieter but would also silently
  change what the canary covers.
