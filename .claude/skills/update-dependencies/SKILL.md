---
name: update-dependencies
description: Update every dependency of this mill-plugins repo end to end - Mill itself, Scala, the JDK, library deps, plugin default versions, the meta-build's dogfooded plugins and GitHub Actions - then pass every CI gate locally and, when asked, land and release. Use when asked to update, bump or upgrade dependencies, Mill, Scala or Java, to "check for new versions", or to fix, supersede or close a Scala Steward "Update for group all" PR.
---

# Update dependencies

Distilled from the previous upgrades: the Scala Steward PR #28 fix (Mill 1.1.9, Scala held back), #31 / 59b4516
(Mill 1.3.1, Scala 3.9.0, everything else) and c7e82e7 (patch bumps plus the plugin defaults #31 missed). Each
rule below exists because skipping it once cost a failed gate, a wrong version or a second commit.

## Ground rules

- Scratch files, logs and JVM temp go under `/var/tmp/update-deps/`, never `/tmp`. Run
  `mkdir -p /var/tmp/update-deps/jtmp && export JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/var/tmp/update-deps/jtmp`
  before anything else.
- Stable releases only. No `-M`, `-RC`, alpha, beta or hash-suffixed builds.
- Edit tracked files only. The checkout usually has untracked scratch (`.lavish/`, `.cellar/`, `docs/plans/...`,
  `.os-lib-watch-sentinel-*`). Drive bulk `sed` from `git ls-files` and stage with `git add -u` or explicit paths.
- Never rewrite history: `CHANGELOG.md` entries, "verified on X" lines in plan docs, and this skill's own examples
  (`.claude/`) stay as they are. Every sweep below excludes them.
- Report what changed and what was held back before committing. Ask before any push: a pushed `v*` tag publishes
  to Maven Central and cannot be undone.

## 1. Orient

```bash
git status --short; git fetch -q --tags origin; git log --oneline -8 origin/main
git log --oneline -5 --grep='chore(deps)'    # then `git show --stat` the latest one that moved Mill (59b4516 today)
```

If the fetch fails with `Permission denied (publickey)` (dead SSH agent), fetch over HTTPS with gh:
`git -c credential.helper= -c credential.helper='!gh auth git-credential' fetch --tags https://github.com/adtechnacity/mill-plugins.git '+refs/heads/*:refs/remotes/origin/*'`.
Base all work on `origin/main`, not on a possibly stale local `main`.

`discover.sh` (next step) lists any open Scala Steward PR (branch `update/all`) and how far it has diverged.
Steward never bumps `.mill-version`, writes the newest Scala even when Mill cannot load it, and adds unrelated
whitespace hunks. Pick one path:

- **Obsolete**: main already has every bump. For each "from X to Y" in the PR body, `git grep -F` the new version.
  If all are there, close it with a comment that names the commit that landed them.
- **Supersede** (the usual case): do the full update on your own branch, then close the PR after merging with
  "Superseded by #N".
- **Fix in place** (when the user asks): `git worktree add -b fix-steward <dir> origin/update/all &&
  git -C <dir> rebase origin/main`. Use the PR body's "Files still referring to the old version numbers" section
  as a checklist. Run every later command as `cd <dir> && ...`, because the shell's cwd does not persist into a
  worktree. Push with `git push --force-with-lease origin fix-steward:update/all`.

## 2. Discover

```bash
bash .claude/skills/update-dependencies/discover.sh 2>&1 | tee /var/tmp/update-deps/discover.log
```

It takes about 15 seconds and is read-only. It prints:

- the latest stable Mill with its Scala, default JVM and bundled libraries;
- the allowed Scala patch, and which Scala minors the newest scalafix-interfaces knows;
- every JDK setting, with a `MISMATCH` line when CI's `setup-java` differs from the JVM Mill runs on;
- `showUpdates` for the root build and for the meta-build (`--meta-level 1`), with the coursier cache bypassed,
  because a cached run misses releases from the last 24 hours;
- the version strings `showUpdates` cannot see: `scoverageVersion`, `.scalafmt.conf`, `pmdVersion`,
  `pulumiSdkVersion`, the stryker4s fallback and fs2;
- the GitHub Actions majors, and the open Steward PR.

A failed lookup prints `FAILED` or `?`, never an empty section. Treat those as unknown, not as "no updates".

For any major bump, read the upstream release notes before editing
(`gh api repos/<owner>/<repo>/releases --jq '.[] | select(.tag_name=="vX.0.0") | .body'`). stryker4s 1.0 renamed
a companion artifact that nothing tracked. Check what exists with the repo1 listing
(`curl -s https://repo1.maven.org/maven2/<group/path>/<artifact>/`) or `maven-metadata.xml`. search.maven.org
returns incomplete version lists.

## 3. Decide

Apply these coupling and hold rules before editing.

| Pin | Rule |
|---|---|
| Mill (`.mill-version`) | Latest stable. Raising it raises the consumer floor, because published plugins compile against `mill-libs` of that version. Say so in the changelog ("consumers need Mill X.Y or newer"). |
| Scala (`scalaVersion`) | Same Scala 3 minor as the target Mill (same TASTy 28.minor). Take the newest stable patch of that minor; never downgrade to match Mill's patch, and never take a newer minor. Mill's build-script compiler cannot read newer TASTy, and only the example tests catch that. Re-explain the skip each time a newer minor shows up. Before a new minor, prefer a scalafix-interfaces release whose `scala3<minor>` key `discover.sh` lists. |
| Java | `./mill` ignores `setup-java` and `JAVA_HOME`. It runs on the target Mill's `defaultJvmVersion` unless `mill-jvm-version` is set. See Java in section 4. |
| os-lib (githooks) | Derived from Mill (`$millOsLibVersion`). Ignore showUpdates' suggestion; it moves with Mill. |
| upickle, mainargs, requests | Keep at Mill's bundled versions (`discover.sh` prints them). Plugins load into Mill's classpath. |
| `mill-libs`, `mill-testkit`, `mill-contrib-*` | Derived from `millVersion`. Never edit. |
| jgit | `org.eclipse.jgit` (core, githooks) and `org.eclipse.jgit.gpg.bc` (githooks): one version, three places. |
| stryker4s | `stryker4s-core`, `stryker4s-command-runner` and `stryker4s-testrunner` (stryker4s.test) share one version. Also update the jar-name asserts in `Stryker4sMillRunnerTest.scala`. The `getOrElse("...")` fallback in `Stryker4sMillRunner.scala` should equal it too. It is a stale `0.21.0` today, a testrunner that does not exist on Central, so set it explicitly; replacing the old version will not find it. |
| fs2 (stryker4s) | `fs2-core` and `fs2-io` must share a version, and the plugin imports `fs2.io`. stryker4s-core brings both at its own version; build.mill overrides only `fs2-core`. Never bump `fs2-core` alone: pin `co.fs2::fs2-io` at the same version, or drop the override. |
| `strykerVersion` (build.mill `MillPluginMutation`) | Leave it. No plugin code reads it. |
| `scoverageVersion` | Two places in build.mill (`MillPluginModule` and `object scoverage`), equal. |
| pulumi | `com.pulumi:pulumi` in build.mill and the `pulumiSdkVersion` default move together. |
| PMD | The `pmdVersion` default drives `pmd-cli` and every `pmd-<lang>` module. |
| Meta-build trio | `mill-release`, `mill-stryker4s` and `mill-cpd` in `mill-build/build.mill` move together to this repo's latest published release, so they always trail by one release. `mill-mima` is independent. |
| Pre-releases | Hold. utest stays where it is until 0.10.0 is final. |

## 4. Apply

Work one category at a time. After each, grep for the old version, including the jar-name forms
(`<artifact>-<old>.jar`, `_3-<old>.jar`), which Steward and plain literal swaps miss:
`git ls-files -z -- . ':!CHANGELOG.md' ':!.claude' | xargs -0 grep -nF '<old>'`.

**Mill.** Ten tracked files move together: the root `.mill-version` and the nine
`*/example/resources/example-*/.mill-version`. Keep the trailing newline:
`git ls-files | grep -E '(^|/)\.mill-version$' | xargs -I{} sh -c 'printf "%s\n" X.Y.Z > {}'`.
Then run `./mill shutdown` so the old daemon does not linger. The next command downloads the new launcher.
The `./mill` wrapper's `DEFAULT_MILL_VERSION` is only a fallback. Refreshing it from
`https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/X.Y.Z/mill-dist-X.Y.Z-mill.sh` is optional. Ask first,
because the upstream script differs from the repo's wrapper.

**Scala.** Replace the literal in tracked files:
`git ls-files -z -- . ':!CHANGELOG.md' ':!.claude' | xargs -0 grep -lF '<old>' | xargs -r sed -i 's/<old-escaped>/<new>/g'`.
That hits both build.mill defs, `cpd/example/resources/example-cpd/build.mill`, and the fixtures in
ScalafixSupportTest (including `scala-library-X.jar` and the `"X-cached"` cache keys), SonarScannerTest,
StrykerModuleTest, DocsSiteTest, CpdSupportTest and PulumiTasksTest. Leave the fixtures that pin other Scala
versions on purpose: 3.8.2 in DocsModuleTest and the example workspaces, and 2.13.x in the scalafix example.
Those are consumer data.

**Libraries.** Edit the `mvn"..."` literals in build.mill and respect the lockstep groups.

**Plugin defaults.** Change `pmdVersion`, `pulumiSdkVersion` and the stryker4s fallback together with their
scaladoc ("Tested against PMD X") and the tests that assert them (`CpdSupportTest` and `PulumiTasksTest`,
including jar names such as `pulumi-X.jar`). In docs, change only lines that state the current default.

**Meta-build.** `sed -i -E 's/(mill-(release|stryker4s|cpd))::[0-9.]+"/\1::X.Y.Z"/' mill-build/build.mill`.

**GitHub Actions.** Bump the `uses: <action>@vN` majors in `.github/workflows/*.yml` and in the consumer template
`githooks/docs/workflows/verify-signing.yml`. Only CI verifies workflow edits.

**Java.** Do not change it unasked: every past upgrade left the JDK alone. Report the `discover.sh` Java block
and propose. Today CI's four `setup-java` blocks (ci.yml twice, release.yml, and the githooks template) say
corretto 21, while Mill 1.3.1 runs zulu:25. Because `./mill` uses its own JVM, `setup-java` alone does not change
what compiles and tests. The options to offer:

- set `java-version` in this repo's workflows to the major of Mill's `defaultJvmVersion`, so the installed JDK
  matches the one that runs;
- pin the JVM with `//| mill-jvm-version: <N>` at the top of build.mill or a `.mill-jvm-version` file;
- leave the consumer template at the consumers' floor unless the user says otherwise.

Confirm any change with `./mill --version`, which prints `java.version` and `java.vendor`. The bytecode floor comes
from Scala (JDK 17), not from this JVM.

**scalafmt.** A version bump usually reformats files. Land the reformat as its own commit on main. Add its SHA to
`.git-blame-ignore-revs` only once the commit is on main: a SHA from a PR branch does not survive the squash
merge, which is why both current entries are dead.

## 5. Verify

Run CI's build-job steps (`.github/workflows/ci.yml`). Log each to a file and judge it by its exit code:

```bash
run() { local n=$1; shift; timeout 1500 ./mill --ticker false "$@" >"/var/tmp/update-deps/$n.log" 2>&1; local rc=$?; echo "$n rc=$rc"; return $rc; }
python3 .github/scripts/quality-report.py selftest &&
  run compile __.compile && run test __.test && run coverage scoverage.xmlReportAll + scoverage.htmlReportAll &&
  run cpd duplication && run examples __.example.testForked &&
  run fmt mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll && run mima __.mimaReportBinaryIssues
```

This takes about 3 to 5 minutes, plus about 2 minutes for the first compile after a Mill bump.

- `__.test` prints many `[error] ... task failed`, `model exploded` and `image not found` lines from tests that
  exercise failure paths. Judge it by the exit code and by `grep -E 'Failed: [1-9]'`, never by grepping "error".
- `java.nio.file.DirectoryNotEmptyException ... testForked.dest` or "Mill launcher failed" is an out-dir race,
  not a test failure. Rerun once.
- `__.example.testForked` is the only gate that catches a Scala/Mill TASTy mismatch
  ("Forward incompatible TASTy file has version 28.9 ..."). Never skip it after a Mill or Scala bump.
- `Not found: type CpdSupport` (or any other plugin trait) in the examples means the local repo is stale. Run
  `./mill clean __.example.pluginArtifacts __.example.localM2Repo` and rerun.
- `Found N misformatted files`: run `./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources`.
- After a meta-build bump, also run `./mill resolve rel._` to prove the dogfooded release plugin loads.
- After an fs2 or stryker4s bump, check the family is aligned:
  `./mill show stryker4s.compileClasspath | grep -oE 'fs2-[a-z]+_3-[0-9.]+\.jar' | sort -u` must show one
  version. Today it shows fs2-core 3.14.0 and fs2-io 3.13.0, a split that already exists.
- CI's `mutation` job mutates the changed modules on PRs only. To mirror it locally, pass repo-relative paths
  (`STRYKER_INCLUDED_FILES=stryker4s/src/atn/mill/X.scala ./mill stryker4s.strykerMutate`). Bare filenames
  mutate zero files and pass vacuously.

## 6. Report and land

Report the bumps (old to new, per module), what was held back and why, the gate results, and anything left
unchecked or only proposed (Java, the launcher). Then land the way the user says. Without direction, propose:

- **Mill, Scala, Java or major library bumps**: `git switch -c chore/upgrade-deps origin/main`, then a PR. The body
  lists the bumps, the holds with their reasons, "Supersedes #N" if one applies, and the locally verified gates.
  Wait for both the `build` and `mutation` checks. `gh pr checks --watch` returns early, so poll
  `gh run view <id> --json status,conclusion,jobs` until it reports `completed`. Then run
  `gh pr merge <n> --squash --delete-branch` and close any superseded Steward PR.
- **Patch-level bumps only**: a single commit on `main` has been accepted.

The commit is a conventional commit with one bullet per module:

```
chore(deps): upgrade Mill to X, Scala to Y and all dependencies     # or: bump <a>, <b> and <c>

- Mill A -> X (build and every example workspace)
- Scala B -> Y, matching the compiler Mill X runs on
- sonar: logback-classic 1.6.5 -> 1.6.6
- meta-build: mill-release, mill-stryker4s, mill-cpd -> 1.4.0
- GitHub Actions: checkout v7, setup-java v6
```

## 7. Release (only when asked)

**Pick the bump.**

- Patch: routine minor and patch library bumps. This has been the custom.
- Propose minor: Mill's floor rises, or a published module's runtime dependency takes a major bump or raises the
  bytecode floor (stryker4s 1.x did both).

The user decides. Steward squash titles ("Update for group all (#N)") are not conventional commits, so `rel`
ignores them; choose the bump explicitly.

**Gate first.** The full section 5 chain must be green on the exact commit you tag. `release.yml` only compiles and
tests before it publishes. If the deps commit went straight to main, push main alone first and wait for CI's
`build` job: `gh run list --branch main -L 1`, then poll `gh run view <id> --json status,conclusion`. Only then tag.

**Write the release by hand.** `./mill rel.patch` writes a thin changelog that also lists the previous
"set next development version" commit, so curate the section:

```bash
git switch main && git pull --ff-only && ./mill rel.unreleased        # preview
printf 'X.Y.Z' > version                                             # no trailing newline
./mill rel.changelog                                                 # writes the ## [X.Y.Z] section
# Curate it: ### Changed (Mill/Scala floor), ### Dependencies ("- **<module>**: <artifact> old → new"),
# ### Other (meta-build, CI). Drop the "set next development version" noise.
git add version CHANGELOG.md && git commit -m 'chore: release vX.Y.Z' && git tag vX.Y.Z
printf 'X.Y.<Z+1>-SNAPSHOT' > version && git add version && git commit -m 'chore: set next development version X.Y.<Z+1>-SNAPSHOT'
```

**Push, after asking.** `git push origin main vX.Y.Z`. Name the tag explicitly: `git tag vX.Y.Z` makes a
lightweight tag, which the README's `--follow-tags` skips. If SSH fails, use the HTTPS form from section 1 with
`push ... main vX.Y.Z`.

**Confirm.** The tag triggers `release.yml`, which publishes and creates the GitHub release from the CHANGELOG
section. "Released" means that workflow is green and all ten POMs (`core release stryker4s cpd scalafix docs
githooks sonar pulumi devx`) return 200 at
`https://repo1.maven.org/maven2/com/adtechnacity/mill-<name>_mill1_3/X.Y.Z/mill-<name>_mill1_3-X.Y.Z.pom`.
Central lags the publish step by 8 to 20 minutes.

**Follow up.** Once the release is on Central, offer `chore(build): dogfood mill-release, mill-stryker4s and
mill-cpd X.Y.Z`. It touches only `mill-build/build.mill`, needs compile, test, duplication, mima and
`./mill resolve rel._`, and needs no release.
