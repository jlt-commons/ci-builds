# ci-builds

Fleet CI for [jlt-commons](https://github.com/jlt-commons): build every project
against the jolt versions that matter, and show the result on one dashboard at
<https://jlt-commons.github.io/fleet/>.

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

Nothing here builds a project. Every project's own `ci.yml` already knows how
to build it, and each accepts a `jolt-version` dispatch input that overrides its
pinned `JOLT_VERSION` for a single run. `fan-out` dispatches those workflows
with the version under test, waits for them, and works out one result per
project. `canary` runs `fan-out` on a schedule against the versions worth
watching, writes the results to the dashboard's data and keeps one issue open
while jolt breaks something.

Reusing each project's real gate matters. `raygui-jlt` needs raylib natives,
`glitter-uikit` needs AppKit and GTK4, `glitter-gl` needs a GL context under
Xvfb. `raylib-ios` is the odd one: its real target is a phone, which no runner
can be, so what it contributes here is its pure scene namespaces run under jolt
on Linux. That still catches a jolt divergence, which is what this is for. A
central rebuild of all that would rot, and would test something other than what
a contributor hits.

The tools are plain Clojure in `src/fleet/`, run by jolt as `jolt <task>`. The
only shell script left is `scripts/setup-app.sh`. The project list is
`fleet.json`: a `projects` array, plus an `excluded` map of repositories that
are deliberately not in it, each with its reason.

### fan-out

`fan-out` takes one version, blank meaning jolt's latest release. It dispatches
every project's `ci.yml` with `-f jolt-version=X`, polls until each run ends,
and then checks which jolt each run really used. That check reads the
`jolt --version` line in each job's log. A release must match the version
asked for. A nightly reports a version like `0.8.27-241-gdd3c4a4b`, and its
`-g` commit must be a prefix of the `vnightly` tag's commit. A run that went
green on some other jolt is a mismatch, never a pass.

Each project ends in one of these:

| result | meaning |
|---|---|
| pass | the run succeeded and a job installed the requested jolt |
| fail | the run failed for any other reason, and the failed step is recorded |
| infra | not about jolt: jolt could not be installed, a dependency could not be downloaded, or a green run's logs could not be read |
| timed-out | the run did not finish before the deadline |
| dispatch-failed | GitHub refused the dispatch, and the first line of its error is kept |
| mismatch | the run succeeded but never ran the requested jolt |
| not-run | GitHub skipped the run, or it was cancelled, never started or waited on a person |

Polling stops at a 45-minute wall-clock deadline. The results go up as a
`results-<version>` artifact. A manual run of the `fan-out` workflow also adds
its result to the dashboard's history through `publish-adhoc`, and leaves the
grid's nightly cells alone.

Dispatch-failed, cancelled and unreadable runs say nothing about jolt, so they
count as "could not run" and never as a pass or a recovery.

### canary

`canary` ticks every 15 minutes, at minutes 7, 22, 37 and 52. The odd minutes
keep it away from the quarter hours, where GitHub's scheduler is busiest and
most likely to delay or drop a run. Each tick works out a version set, which is
jolt's latest release plus the nightly when the nightly qualifies:

- The nightly is jolt's rolling `vnightly` prerelease, published since
  [jolt-lang/jolt#824](https://github.com/jolt-lang/jolt/pull/824). It is
  dropped when it is older than `vars.NIGHTLY_MAX_AGE_HOURS` (default `48`),
  because jolt publishes it only after its own downstream-library checks pass.
  A lagging library withholds the new artifact while the previous `vnightly`
  stays up and installs perfectly, so a green nightly row could mean "main is
  healthy" or "main broke on Tuesday and this is Tuesday's binary".
- It is also dropped when it is the same commit as the release. jolt tags
  `vnightly` at whatever main was when the job ran, which is often the release
  tag itself, and one build should not show up as two answers. The canary
  compares the tags' commits at run time.

What each project pins is not a row. It is read from the project's own latest
push run on its default branch and shown as the "Own pin" column.

Three gates decide whether a tick tests anything:

- The interval: `vars.CANARY_INTERVAL_HOURS`, default `0`, which means no gate.
  A cron cannot be a variable, since GitHub requires a literal, so the schedule
  ticks and this decides how often a tick may do work. It is measured from the
  last run that built something.
- The green cache: a version set that already passed is not retested. The
  Actions cache holds it, so nothing is committed back. The nightly's
  `published_at` is part of the key. The literal string `nightly` is the same
  today as yesterday, so without it the first green would cache a key every
  later run recomputes.
- The retry backoff: a set that did not pass is tried once per window,
  `vars.CANARY_RETRY_HOURS` (default `6`). A new version set has a new key and
  runs on the next tick, while a known-bad one does not dispatch every project
  every 15 minutes.

A manual dispatch skips all three.

The legs run one version at a time. Each leg dispatches every project's `ci.yml`
on its default branch, and two legs in flight together would dispatch the same
project twice within seconds, which cancels the earlier run in any project whose
concurrency group cancels in progress.

Two more things happen on every tick, including a gated one. The `status` job
merges the results into the dashboard's data, so the pin column keeps up with
each project's own CI while jolt stands still. It commits only when the content
changed or the last commit is 6 hours old, a heartbeat of about 4 commits a day
that keeps `generated_at` moving, so the dashboard's staleness banner fires only
when the canary has really stopped. The `report` job opens, updates or closes the
single issue titled "canary: jolt breaks the fleet". It opens when a project
fails, mismatches or times out on a version, and closes itself with a recovery
comment once a later run passes, so an open issue means "broken right now" and
not "broke once". When every problem is infra, the issue says so and names the
failed step. Infra covers jolt that could not be installed, a dependency that
could not be downloaded, a run that could not start (dispatch failed or
cancelled) and logs that could not be read. None of those is a finding about
jolt.

The tools run on their own jolt, `vars.TOOLS_JOLT_VERSION` (default `0.8.28`),
pinned in the workflows and never the jolt under test. A release published
overnight must not change the thing that measures releases.

## Dependency graph

`fleet:deps` maps which library pins which across every public jlt-commons and
jolt-lang repository, so that when a library gets a new tag you can see
everything that has to follow it. The `deps` workflow runs it hourly and
commits `deps.json` to the `status` branch, beside `status.json`. The page at
<https://jlt-commons.github.io/fleet/deps.html> reads it.

It reads each repository's root `deps.edn`, tags and default branch head
through GraphQL, one query per 25 repositories. Every git coordinate that
names a repository in either organization becomes an edge from the dependency
to the dependent. It resolves by `:git/url` first, then by an
`io.github.OWNER/NAME` or `com.github.OWNER/NAME` symbol. `:local/root` (dev
overrides, subprojects) and `:mvn/version` coordinates are not edges, and
neither is anything in a private or archived repository.

Each pin is one of:

| state | meaning |
|---|---|
| current | the latest semver tag, its commit, the default branch head, or a commit that contains the latest tag |
| behind | an older tag, or a commit the latest tag is not in; for an untagged library, anything but the head |
| unknown | a `:git/ref`, a tag the library does not have, or a commit that could not be compared |

A bare sha on a tagged library that matches no tag and is not the head is
checked with GitHub's compare API, since it may sit after the last release.

For each library, `deps.json` also carries the order to retag its dependents.
If B pins A and C pins B, a new A means B moves its pin and retags, then C
moves its pin to the new B and retags. Libraries are grouped in waves: every
library in a wave pins only the changed library or libraries from earlier
waves, so a wave can be released together. A library that pins two libraries
from the graph waits for the later one. Libraries caught in a dependency cycle
cannot be ordered and are listed separately.

```
GH_TOKEN=$(gh auth token) jolt fleet:deps --out deps.json
```

## Dashboard

<https://jlt-commons.github.io/fleet/> has one row per project and three
columns:

| column | what it shows |
|---|---|
| Own pin | the project's latest push run on its default branch, with the version its `ci.yml` pins |
| Latest release | the project built against jolt's newest release |
| Nightly | the project built against `vnightly`, when the canary tested one |

A cell is one of the results in the fan-out table, shown as "pass", "fail at
<step>", "could not run: infra", "timed out", "dispatch failed", "ran on the
wrong jolt" or "not tested yet". Each links to its run, and a row expands to the
last runs in each column, ad-hoc fan-outs included. The header says which
release is latest, which nightly commit was tested and how old it is, and when
the data was generated. After 12 hours without an update the page warns that the
canary may have stopped. Below the grid, "Not in the fleet yet" lists the
organization's repositories that no run covers, each with a reason: excluded,
no `ci.yml`, no `jolt-version` input, or ready but not yet in `fleet.json`.

The data lives on this repository's orphan `status` branch as `status.json` and
`history.json`, both schema 1. The page fetches them from
`https://raw.githubusercontent.com/jlt-commons/ci-builds/status/status.json` and
`.../history.json`. That host sends `Access-Control-Allow-Origin: *` and caches
for 300 seconds, so the page can lag the branch by up to five minutes. The page
source is in
[jlt-commons.github.io](https://github.com/jlt-commons/jlt-commons.github.io),
not here.

## Using it

To try a candidate jolt across everything, go to Actions, then fan-out, then Run
workflow, and give it a version (blank means jolt's latest release, `nightly` means
`vnightly`). Nothing is committed to any project. Each one builds at its own
HEAD with the version you named, and the result goes into the dashboard's
history.

```
gh workflow run fanout.yml --repo jlt-commons/ci-builds -f jolt-version=nightly
```

To try one project, dispatch that project's own `ci.yml` with the
`jolt-version` input. It is the same mechanism for one repository.

To run the canary now, dispatch `canary`, optionally with a version. A manual
dispatch skips the gates.

To adopt a new jolt, run `fan-out` against it, then, when green, move each
project's `JOLT_VERSION` pin in one reviewed commit per repository. Moving a
pin is the cutover commit for a breaking jolt change, which is the point of
pinning rather than floating.

## Adding a project

1. Give its `ci.yml` a `workflow_dispatch` input named `jolt-version` and a
   workflow-level `JOLT_VERSION` pin that reads it (see below). A project whose
   CI never touches jolt would report a green here that means nothing, which is
   worse than being absent.
2. Add the repository to the jlt-commons-ci App installation. The App is scoped
   to selected repositories, and the token the fan-out mints reaches only
   those. A project outside the installation fails at dispatch with a 404 that
   reads like a missing workflow. This is a settings change, at
   `https://github.com/organizations/jlt-commons/settings/installations`, and
   no API call in this repository can do it.
3. Add its name to `projects` in `fleet.json`.
4. Run one fan-out and confirm the project dispatches and reports.

Until step 3, the dashboard lists the repository under "Not in the fleet yet"
with the reason it is not covered.

## The pin convention

Every project carries a workflow-level pin:

```yaml
env:
  JOLT_VERSION: ${{ inputs.jolt-version || '0.8.1' }}
```

A floating `latest` lets a release published overnight turn CI red on a tree
nobody touched, which is the same reason clj-kondo, clojure-lsp and babashka
are pinned in those workflows.

The shared docs-site workflow (`.github/workflows/site.yml`) follows the same
convention. It takes a `jolt-version` input that defaults to a fixed release
(`0.8.19` at the time of writing), so every project's site builds with the
same jolt until that default is moved here in one reviewed commit. A project
can still pass `jolt-version` to try another release on its own site.

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
and reaches only the repositories the App is installed on, and is what lets someone trigger a fleet
build without borrowing another person's identity. A PAT would attribute every
dispatched run to whoever created it and would die with their access.

`APP_ID` and `APP_PRIVATE_KEY` are repository secrets here. Repository rather
than organization secrets because this is a free organization, where org
secrets cover public repositories only, and because the credential's blast
radius should be the one repository that needs it.

## Working on it

```
jolt test           # run the suite, exit 1 on any failure
jolt tasks          # list every task with its doc
jolt nrepl-server   # a REPL for editors, writes .nrepl-port
```

The tasks are in `deps.edn`: `test`, and the `fleet:` tools the workflows call
(`fleet:own-pin`, `fleet:uncovered`, `fleet:status`, `fleet:refresh`,
`fleet:publish`, `fleet:fanout`, `fleet:plan`, `fleet:report`, `fleet:deps`,
`fleet:deps-publish`). The prefix keeps
them clear of jolt's own subcommands. Code is in `src/fleet/` and tests in
`test/fleet/`. The ones that touch GitHub take `GH_TOKEN` from the environment,
and the network sits behind injected functions, so the suite runs offline.

Two jolts are involved. The tools run on the tool jolt, pinned as
`TOOLS_JOLT_VERSION` in each workflow, with `vars.TOOLS_JOLT_VERSION` as an
override. The jolt under test is only ever a `jolt-version` input to a project's
`ci.yml`. Locally, whatever `jolt` is on your path runs the tools, so a new
release can change the tools' behavior on your machine before it does in CI.

## Known gaps

- The cron runs less often than it says. GitHub ran the old hourly cron
  only 3 to 4 times a day between 2026-11-10 and 2026-11-19. That is why the
  canary ticks every 15 minutes, and the real rate under the new schedule is
  still unmeasured. Check it a week after this merges, from the run history of
  `canary`, and move the schedule again if ticks still go missing.
- The canary polls rather than being told. Jolt publishes a release every
  few days, plus a nightly, and a poll notices within a tick or two at best. A
  `repository_dispatch` step in jolt's release workflow would make it
  immediate. That is a request to jolt's maintainer, since jolt is a different
  organization where we are read-only.
- Two demos are excluded. `raylib-jolt-demo` and `raylib-ios-demo` wait on
  the jlt-commons-ci App installation, because until they are installed they
  fail at dispatch and would open a false issue. They show under "Not in the
  fleet yet". `fleet.json` lists the other exclusions.
- A repository without a `jolt-version` input cannot join. It is listed as
  "Not in the fleet yet" until its `ci.yml` takes one.
- A stale nightly drops the nightly row. Past 48 hours the canary stops
  testing it, which also means an open issue about a nightly failure can close
  as recovered while the project is still broken on main.
- A permanently unreadable project keeps its last cell. When a collector
  cannot read a project, the dashboard keeps the previous cell and reason and
  warns on stderr, with no age limit, so the page can show an old good result
  for a project whose runs have become unreadable.
- `glitter-gl` has a shim a single pin cannot fully exercise.
  `glitter-gl.ffi-compat` resolves `ffi/write`'s argument order at load and has
  two branches; a run pinned to one jolt only ever takes one of them. A
  two-entry matrix over an old and a new jolt would cover both.
- `fleet.json` is a hardcoded list, so a new repository is invisible to the
  fan-out until someone edits it. The dashboard still lists it under "Not in the
  fleet yet". Discovery by topic or by the App's installation would be quieter
  but would also silently change what the canary covers.

## Licence

Copyright (c) 2026 Burin Choomnuan.

Distributed under the Eclipse Public License 2.0. The full text is in
`LICENSE`. SPDX identifier: `EPL-2.0`.
