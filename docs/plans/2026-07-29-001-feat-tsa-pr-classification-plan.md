---
title: "feat: TSA (Tell/Show/Ask) PR classification for the githooks plugin"
type: feat
status: active
date: 2026-07-29
origin: docs/brainstorms/2026-07-29-tsa-pr-classification-requirements.md
---

# feat: TSA (Tell/Show/Ask) PR classification

## Overview

Add a PR classifier to the `githooks` plugin: pluggable signals vote a minimum tier
(Tell/Show/Ask) over a diff range; a Mill task emits the tier plus per-signal votes as JSON
and a human-readable summary; a pre-push hook prints it as advisory feedback; a documented
single privileged GitHub workflow classifies from base-branch code, applies labels, a check
run, review requests, and (flag-gated, trusted-bot-only) auto-merge.

The plugin side is a pure classifier. All GitHub mutation lives in the reference workflow
(R19) — the plugin never talks to the GitHub API.

## Problem Frame

Every PR gets the same review ceremony regardless of risk (see origin doc). Tiers route
review effort: Tell auto-merges bot dependency bumps, Show/Ask are advisory routing labels
in v1. Key origin decisions carried forward: Show-as-floor voting with explicit
downgrade-capable signals, bot-identity AND dep-only-diff as the only auto-merge path,
shadow-first rollout, R7–R9/R11 deferred to v2
(see origin: docs/brainstorms/2026-07-29-tsa-pr-classification-requirements.md).

Two origin positions were revised during plan review (origin doc updated to match):
- **Tell is label-reachable for humans**: the docs-only/test-additive predicate is a second
  downgrade-capable signal, so a human docs PR earns `tsa:tell`; auto-merge eligibility
  remains strictly R15/bot-gated. (Otherwise Tell collapses to a bots-only label, contradicting
  the origin tier table.)
- **Single privileged workflow in v1**: no v1 signal executes PR code, and the privileged side
  had to recompute from base-branch code anyway, so the unprivileged-classify/artifact/
  workflow_run split bought only risk (artifact hardening, stale races, broken fork path).
  The split returns in v2 when R7–R9 must execute PR builds.

## Requirements Trace

- R1–R3: engine, vote semantics, split abstention → Unit 3
- R4–R6, R15: default signals → Units 2, 4
- R10: signal customization via one `def` → Unit 5
- R12: pre-push advisory → Unit 5
- R13, R14, R16–R18: privileged workflow, labels, check run, routing, auto-merge → Unit 6 (workflow YAML, not Scala)
- R19: documented manual setup → Unit 6
- Shadow-phase success criterion → Units 5 (explicit-range mode) and 6 (dry-run script)

## Scope Boundaries

- Show/Ask are advisory; no enforcement gate check (v2, with R11 ownership). One exception:
  repos that enable the auto-merge flag MUST mark the TSA check run as a required status
  check (closes the escalation-vs-merge race; see decisions).
- Human-authored PRs never auto-merge in v1 (`autoMergeEligible` only via R15).
- No R7–R9 signals (quality/coverage/mutation) — documented as R10 custom-signal examples only.
- GitHub only; no setup automation beyond documentation.

## Context & Research

### Relevant Code and Patterns

- `githooks/src/atn/mill/GitHooksModule.scala` — command shape: `Task.Command(exclusive = true)` + `EvaluatorProxy`; overridable-`def` config style; optional/defaulted command args precedent (`install(evaluator, force)`); `validModules` module-tree traversal.
- `githooks/src/atn/mill/GitInstall.scala` — hook script templating (`writeNext`, 0755, sh/Windows split); `writePrePushHook` is the advisory integration point.
- `githooks/src/atn/mill/GitValidateCommit.scala` — `FirstLineRE`, `DefaultTypes`; `checkBreaking` is a TODO stub; parse result not exposed (R4 refactor target).
- `githooks/src/atn/mill/GitPrepCommit.scala` — jGit diff precedent (`Git.diff()`, `DiffFormatter`); first-path-segment module attribution.
- `githooks/src/atn/mill/WorkDone.scala` — upickle-serializable result ADT precedent for the classification output.
- `core/src/atn/mill/GitRepo.scala` — `Result`-wrapped repo access (used, not extended — see decisions).
- `release/src/atn/mill/ReleaseModule.scala` + `ConventionalCommit.scala` — commit-range walking (`git.log().addRange`) and a second copy of the commit grammar (consolidation deliberately deferred).
- `sonar/src/atn/mill/SonarScanner.scala` — env-var-name-as-`def` idiom for CI-injected values.
- Tests: `githooks/test/src/atn/mill/GitHooksModuleTest.scala` (UnitTester fixtures with `TestRootModule` + `Discover`, hook-script string assertions); `devx/test/src/atn/mill/CodeSceneTest.scala` (ScalaCheck). No jGit fixture-repo tests exist yet — Unit 1 establishes the pattern.
- Build: `MillPluginModule` in `build.mill` (Mill API provided-scope, MiMa vs 0.3.0, per-module `mimaBinaryIssueFilters` precedent on `GitInstall`).
- CI: `.github/workflows/ci.yml` — shallow checkout (reference workflow must fetch history). **Load-bearing pitfall:** Steward's PRs are created with `GITHUB_TOKEN` and therefore fire no `pull_request`/`pull_request_target` events (ci.yml already works around this with explicit dispatch) — see decisions for the required bot-credential prerequisite.

### Institutional Learnings

- No `docs/solutions/` exists. From project memory: `exclusive = true` commands cannot take an `Evaluator` param in ExampleTester integration tests; ExampleTester `/** Usage */` blocks are parsed as Scala (no quotes/brackets/`for`); MiMa locks published APIs — keep new public surface minimal and deliberate.
- In-flight work: branch `fix/scalafix-classloader-leak` has uncommitted changes to `GitHooksModule.scala`/`GitInstall.scala`. TSA work starts from a fresh branch after that lands.

## Key Technical Decisions

- **All new code lives in `githooks`, including the diff plumbing**: signals AND the GitDiff helpers have exactly one consumer in v1. Publishing GitDiff from `core` (depended on by every plugin, MiMa-locked) would contradict the one-consumer rule used to keep the signal API out of core. Graduate GitDiff to core if/when `release` adopts it.
- **Do not move `release`'s `ConventionalCommit` in v1**: R4 is served by refactoring `GitValidateCommit` (githooks-owned) to expose its parse. Noted as future cleanup.
- **Diff data includes per-file hunk text**: R15's dependency-only predicate inspects hunk content, so the changed-file record carries a patch-text accessor (lazily computed), not just path/type/line-count metadata.
- **Vote-level suppression, not signal-level** (narrowed from the first plan draft to preserve origin R2's quorum): each vote carries an `overridableByDowngrade` flag set by the emitting signal. R4 marks its *unparseable-commit → Show* vote overridable but its *breaking-change → Ask* vote NOT overridable; R5 marks its *build-file → Ask* and *size* votes overridable, its *test-deletion → Show* not. A downgrade-capable signal wins only when no non-overridable Show/Ask vote exists. Consequence: a bot dep bump whose commits carry `BREAKING CHANGE` classifies Ask and never auto-merges.
- **Two downgrade-capable signals**: R15 (bot + dep-only; the only signal that sets `autoMergeEligible`) and the docs-only/net-additive-test-only predicate (Tell label for trivially safe human PRs; never auto-merge-eligible). Custom signals are never suppressed and always escalate.
- **Unparseable commit → Show; merge commits excluded**: R4 iterates first-parent `merge-base..HEAD` commits; when CI provides the PR title (squash-merge repos discard branch messages), R4 classifies the title as an additional input — the origin's squash caveat is otherwise an accepted gap, documented.
- **Tell predicates require a non-empty diff**; empty diff yields the Show floor.
- **Rename detection on; renames classify as modifications**; any rename, binary, submodule, or mode-only change fails R15's confinement.
- **Dep-only predicate is a security boundary, specified as such**: only the version literal of an *existing* dependency declaration may change. Coordinate swaps, added/changed resolvers or repository URLs, plugin-import changes, and any non-version token change fail confinement. Unit 4 carries adversarial negatives, not just the benign Steward fixture.
- **The classifier accepts explicit base/head/author/title inputs**: CI passes them from the trusted event payload; local runs default to merge-base-with-main and no author (R15 abstains; advisory output states CI is authoritative). Explicit-range mode + head-ref fetching makes the shadow dry-run executable.
- **Single privileged workflow, base-code-only execution**: the workflow checks out base-branch code ONLY and fetches the PR head purely as git data (`refs/pull/N/head`) consumed via the explicit base/head args. It never runs Mill (or any build tool) from a workspace containing head content — that constraint is stated in the shipped YAML. Handles fork PRs naturally; fork PRs are labeled but never auto-merged.
- **Bot PRs must be created with App/PAT credentials**: PRs created via `GITHUB_TOKEN` fire no workflow events at all (this repo's known pitfall), so the reference setup requires Scala Steward (or any trusted bot) to run with a GitHub App or PAT. Documented as a hard prerequisite for the flagship path.
- **Bot identity pinned beyond login**: auto-merge re-verification matches the immutable numeric user ID (or requires the `login[bot]` App-account form); docs warn that bare machine-account logins are vulnerable to rename/re-registration reuse.
- **Auto-merge race closed by required check**: enabling the auto-merge flag requires marking the TSA check run as a required status check, so a post-enable push cannot merge before the new head's verdict lands. Auto-merge disablement on escalation is one-way latching.
- **Missing/failed classification in CI → fail-safe `tsa:show` + failed check run.**
- **Override labels win for display; auto-merge stays R15-only**: `tsa:override:*` suppresses the computed label, is never clobbered, cannot grant auto-merge; anyone with triage+ can apply one — accepted in v1 because overrides are display-only; revisit before any v2 gate consumes them.
- **Pre-push advisory always exits 0 and parses its stdin**: the hook template gains a `while read local_ref local_sha remote_ref remote_sha` block gating the advisory call (skip tags, deletions, pushes of main, absent local main), warn-and-continue on any classifier error. CI keeps fail-loud.
- **Review requests fire on tier transitions** (entry into Ask), not states; de-escalation does not withdraw requests.
- **R6 fan-out gets a knob**: a module-count threshold def (default 2) and an exemption — docs-only/test-only touches inside a module don't count toward fan-out. Without a knob, Ask is modal *by construction* in a multi-module repo and the shadow phase could observe but never fix it.

## Open Questions

### Resolved During Planning

- API/code placement: everything in githooks (above); core untouched in v1.
- CI architecture: single privileged workflow (above); split deferred to v2 with R7–R9.
- Fork-PR path (origin deferred question): handled by the single workflow — labeled, never auto-merged.
- Shadow-phase mechanism: explicit-range mode + per-PR `refs/pull/N/head` fetch + documented `gh` script.
- R4 aggregation: max across parseable first-parent commits, plus PR title when provided; unparseable → Show (overridable vote).
- R5 defaults: Tell (downgrade-capable) requires docs-only or net-additive test-only; > 400 changed LOC or > 20 files → Ask (overridable); everything overridable by consumers.
- R6 unmapped paths: no fan-out vote of their own; root build files and workflows are Ask via R5's file classes.

### Deferred to Implementation

- Exact hunk-shape grammar for "version literal of an existing dependency" — iterate against real Steward diffs (including grouped multi-dependency bumps, per `.scala-steward.conf`); Unit 4's fixtures (benign + adversarial) are the acceptance tests.
- Check-run conclusion mapping for advisory outcomes (`neutral` vs `success`) — decide when writing the workflow.
- jGit rename-detection score threshold — tune against fixture repos.
- Whether JSON lands on stdout via `show`/println or only in `Task.dest` — pick one and document it as the contract the workflow and shadow script consume.

## High-Level Technical Design

> *Directional guidance for review, not implementation specification.*

```text
githooks (new files, package atn.mill):
         GitDiff       mergeBase(a, b) / changedFiles(base, head): path, change type,
                       binary/submodule flags, +/- counts, rename-detected,
                       patchText (lazy)  / firstParentCommits(base, head)
         Tier          = Tell < Show < Ask                       (ordered ADT)
         SignalVote    = Vote(tier, overridableByDowngrade) | Abstain | Failed(msg)
         TsaSignal     { name; downgradeCapable; vote(ctx): SignalVote }
         TsaContext    { changedFiles; commits; prTitle: Option;
                         authorLogin: Option; inCi: Boolean; config }
         TsaResult     { tier; votes: Map[name, SignalVote];
                         autoMergeEligible: Boolean }            (upickle, WorkDone precedent)
         TsaEngine.classify(signals, ctx):
           floor = Show
           blocking  = votes that are Show/Ask and NOT overridableByDowngrade
           downgrade = fired downgrade-capable signals (R15, docsOnly)
           if downgrade.nonEmpty && blocking.isEmpty -> Tell
             (autoMergeEligible = R15 fired)
           else tier = max(all votes, floor)
           Failed(escalator) in CI -> floor stays Show, vote reported
```

Decision matrix (engine outcomes, encoded as tests in Unit 3):

| Inputs | Result |
|---|---|
| No votes at all | Show (floor) |
| Only R15 fires (bot + dep-only) | Tell, `autoMergeEligible = true` |
| R15 fires + R4 breaking-change Ask (non-overridable) | Ask — no auto-merge |
| R15 fires + R4 unparseable Show (overridable) | Tell (the Steward case) |
| R15 fires + custom signal votes Ask | Ask |
| Docs-only human PR, nothing else votes | Tell, `autoMergeEligible = false` |
| Docs-only + any non-overridable Show/Ask vote | that vote's tier |
| Escalating signal Failed in CI | ≥ Show, failure listed in votes |

## Implementation Units

- [ ] **Unit 1: git diff plumbing (githooks)**

**Goal:** Merge-base, changed-file listing (rename detection, binary/submodule flags, line counts, lazy patch text), and first-parent commit-range listing.

**Requirements:** R1, R4–R6, R15 (data source for all signals)

**Dependencies:** None (start after the in-flight githooks branch lands)

> **Coordination note (2026-08-20):** `GitDiff.scala` is now **owned by the conditional-commit-signing plan** (`docs/plans/2026-08-20-001-feat-conditional-commit-signing-plan.md`, its Unit 1), which builds the accessors signing consumes (staged/per-commit/merge-combined views, raw-diff mode, Mill-API-free error types, MiMa-excluded until surface review). TSA-only accessors — `firstParentCommits`, submodule flags, merge-base — are deliberately **not** pre-built there; this unit **extends the same file** with them when TSA lands (or creates the file to the signing plan's spec plus these accessors if TSA somehow lands first). Follow the signing plan's constraints when extending: no `mill.api` types, raw diffs. The same plan owns the pre-push stdin `while read` block's shape (its enforcing `verifyRange` call comes first); Unit 5 here adds the exit-0-guarded advisory line inside that existing block when present.

**Files:**
- Create: `githooks/src/atn/mill/GitDiff.scala`
- Test: `githooks/test/src/atn/mill/GitDiffTest.scala`

**Approach:** jGit `RevWalk` merge-base + `DiffFormatter`/`DiffEntry` with rename detection; per-file patch text exposed lazily (R15 needs hunk content — feasibility-review finding). Establish the repo's first jGit fixture-repo test pattern (`Git.init` in `os.temp.dir()`, scripted commits).

**Patterns to follow:** `GitPrepCommit` diff usage; `ReleaseModule.unreleasedCommits` range walking; `GitRepo` `Result`-wrapped access.

**Test scenarios:**
- Happy path: linear branch off main → merge-base is fork point; changed files carry correct +/- counts, change types, and patch text.
- Edge: rename-only change reports as rename (not add+delete); binary file flagged with no line counts; submodule bump flagged; empty range → empty list.
- Edge: merge commit on the branch → first-parent commit listing excludes mainline commits.
- Error path: unrelated histories / no merge-base → `Result.Failure`, not an exception.

**Verification:** New tests pass; MiMa clean (new classes only).

- [ ] **Unit 2: expose the conventional-commit parse**

**Goal:** `GitValidateCommit` exposes a parsed header (type, scope, breaking flag) and BREAKING CHANGE footer detection; validation behavior unchanged.

**Requirements:** R4

**Dependencies:** None

**Files:**
- Modify: `githooks/src/atn/mill/GitValidateCommit.scala`
- Test: `githooks/test/src/atn/mill/GitHooksModuleTest.scala`

**Approach:** Extract the `FirstLineRE` match into a parse function on the companion; add footer scanning. Leave `checkBreaking`'s validator behavior as-is. Do not touch `release`'s copy.

**Test scenarios:**
- Happy path: `feat(core)!: x`, `chore: y` parse to expected type/scope/breaking.
- Edge: `BREAKING CHANGE:` footer detected; absent footer not flagged.
- Error path: Steward-style `Update foo to 1.2.3` → parse yields none; existing validation messages unchanged.

**Verification:** Existing validator tests pass; MiMa filter only if a public constructor changes (precedent: `GitInstall` filter).

- [ ] **Unit 3: TSA domain model and engine**

**Goal:** Tier/vote ADTs (votes carry `overridableByDowngrade`), signal trait, upickle-serializable result, engine implementing Show-floor, downgrade quorum, and split abstention semantics.

**Requirements:** R1, R2, R3

**Dependencies:** None (pure domain; parallel with Units 1–2)

**Files:**
- Create: `githooks/src/atn/mill/Tsa.scala`
- Test: `githooks/test/src/atn/mill/TsaEngineTest.scala`

**Approach:** Encode the decision matrix verbatim. The vote-level `overridableByDowngrade` flag is the suppression mechanism (no default-vs-custom signal marker needed — custom signals simply emit non-overridable votes by default). `TsaContext.inCi` selects configured-but-failed flooring. Serialize per `WorkDone`'s upickle pattern; include `prTitle`.

**Execution note:** Implement the decision matrix test-first — it is the product's semantics.

**Test scenarios:**
- All eight decision-matrix rows, verbatim.
- Property (ScalaCheck): adding any signal emitting non-overridable votes never lowers the tier; result invariant to signal order.
- Edge: duplicate signal names → deterministic behavior (pick and test); two downgrade signals firing together → Tell, `autoMergeEligible` iff R15 among them.
- Error path: signal throwing during vote captured as `Failed`, not a crash.

**Verification:** Matrix and property tests pass; JSON round-trips.

- [ ] **Unit 4: default signals**

**Goal:** The five v1 signals: commit type (R4), diff shape (R5), docs-only downgrade (R5-Tell), module fan-out (R6), trusted-bot dependency (R15).

**Requirements:** R4, R5, R6, R15

**Dependencies:** Units 1, 2, 3

**Files:**
- Create: `githooks/src/atn/mill/TsaSignals.scala`
- Test: `githooks/test/src/atn/mill/TsaSignalsTest.scala`

**Approach:** Pure functions over `TsaContext`. Vote flags per the decisions (R4: unparseable-Show overridable, breaking-Ask not; R5: build-file/size overridable, test-deletion not). Docs-only signal: downgrade-capable, fires on non-empty docs-only or net-additive-test-only diffs. R6 takes the path→module mapping as data, votes Ask at ≥ `tsaFanOutThreshold` modules (docs/test-only touches within a module don't count). R15: author identity (login for local config; the workflow re-verifies immutable ID) AND dependency-only confinement via hunk inspection — only version literals of existing dependency declarations may change.

**Test scenarios:**
- **Steward fixture:** `build.mill` version-bump diff, bot author → Tell + `autoMergeEligible`; same diff, no author → Show; same diff, human author → Ask; grouped multi-dependency bump → Tell.
- **Adversarial R15 negatives (security acceptance tests):** coordinate swap (`org::artifact` change) → no fire; added resolver/repository URL → no fire; version string carrying an injection payload shape → no fire; dep bump + one source file → no fire; dep bump including binary/submodule/rename → no fire.
- R4: `docs:` commits → overridable Tell vote; `feat!` in range → non-overridable Ask; unparseable commit → overridable Show; PR title `feat!:...` with `chore:` branch commits → Ask (title input).
- R5: docs-only → downgrade fires; test deletion → non-overridable Show; workflow file → Ask; 401+ LOC → Ask; test-file rename → modify, not deletion.
- R6: two source modules → Ask; one module + root README → no vote; two modules where one is docs-only touches → no vote (exemption).

**Verification:** Steward fixture passes all variants; adversarial negatives all refuse to fire.

- [ ] **Unit 5: TsaModule wiring, config defs, and pre-push advisory**

**Goal:** The user-facing Mill surface: a `tsa` command (optional base/head/author/title args; JSON + one-line human-readable summary), overridable config defs, and the stdin-gated advisory block in the pre-push hook.

**Requirements:** R1, R10, R12; enables the shadow-phase criterion

**Dependencies:** Units 3, 4

**Files:**
- Create: `githooks/src/atn/mill/TsaModule.scala`
- Modify: `githooks/src/atn/mill/GitHooksModule.scala`, `githooks/src/atn/mill/GitInstall.scala`
- Test: `githooks/test/src/atn/mill/GitHooksModuleTest.scala`

**Approach:** Command mirrors the `Task.Command(exclusive = true)` + `EvaluatorProxy` idiom (defaulted args precedent: `install(force)`). Evaluator builds the R6 path→module mapping from `rootModule` traversal with `moduleDir` (`validModules` names are insufficient). Config defs follow the scaladoc'd-def style: `tsaSignals`, size thresholds, `tsaFanOutThreshold`, `tsaTrustedBotLogins`, `tsaBaseBranch`, path-glob sets. Output: JSON (document the stdout-vs-`show` contract) plus a one-line summary (tier + votes) for humans (R1). Hook integration: a `while read local_ref local_sha remote_ref remote_sha` block in `writePrePushHook` gating the advisory call — skip tags/deletions/main/absent-main — guarded to always exit 0 (feasibility-review finding: a single advisory line cannot implement the skip conditions).

**Test scenarios:**
- Happy path: UnitTester fixture → `tsa` emits valid JSON with tier, votes, `autoMergeEligible`, plus the summary line.
- Edge: explicit base/head args classify a historical range; no-merge-base → command fails with a clear message (CI fail-loud) while the hook block still exits 0 (script-level assertion).
- Integration: hook-script assertions — stdin-parsing block present, ordered relative to existing extra commands, skip conditions and exit-0 guard visible in the script text.
- Error path: unknown author login → R15 abstains, JSON records it.

**Verification:** `./mill githooks.test` green; generated pre-push script contains the gated advisory block.

- [ ] **Unit 6: reference workflow, shadow-phase script, and docs**

**Goal:** R19 deliverable: one privileged workflow implementing R13, R14, R16–R18, plus the shadow dry-run script and setup docs.

**Requirements:** R13, R14, R16, R17, R18, R19; shadow-phase criterion

**Dependencies:** Unit 5 (output schema is the contract)

**Files:**
- Create: `githooks/docs/tsa.md`, `githooks/docs/workflows/tsa.yml`, `githooks/docs/tsa-shadow.sh`
- Modify: `README.md` (pointer)

**Approach:** `tsa.yml`: `pull_request_target` (opened/synchronize/reopened/edited-with-base-change) + concurrency group per PR; checks out **base-branch code only**, fetches `refs/pull/N/head` as pure git data, runs `tsa` with payload base/head/author/title — never executes Mill against head content (constraint stated in the YAML). Applies tier + module labels (respecting `tsa:override:*`; label names built from base-branch module data, never from PR-derived strings), posts the R16 check run, requests core-contributor reviews on entry into Ask, and — only when the auto-merge flag is on — verifies the author's immutable user ID / `[bot]` form and enables auto-merge (fine-grained PAT or GitHub App: `contents:write` + `pull_requests:write`, single-repo, stored as a repo/environment secret, rotation note; not `GITHUB_TOKEN`, whose merges fire no workflows). Classifier failure → `tsa:show` + failed check run. Docs cover: label creation; the **bot-credential prerequisite** (Steward must run with App/PAT or its PRs fire no events — this repo currently uses `GITHUB_TOKEN` + dispatch and must migrate for the flagship path); marking the TSA check required when the flag is on; the CODEOWNERS/auto-merge interaction; the one-way latch; override-label authority (triage+, display-only). `tsa-shadow.sh`: `gh pr list --state merged --json` iteration; fetches `refs/pull/N/head` per PR (skip-and-count when unavailable); passes PR author login and title as discrete quoted arguments (no eval/string-built commands); emits a tier-distribution table.

**Test expectation: none** — YAML/docs/script deliverables; exercised by adopting the workflow in this repo during the shadow phase.

**Verification:** actionlint passes on `tsa.yml` (required — run in this repo's CI once adopted); shadow script produces a distribution table against this repo's history; docs state the base-code-only and bot-credential constraints verbatim.

- [ ] **Unit 7: example integration test**

**Goal:** ExampleTester coverage proving the published plugin exposes the `tsa` surface.

**Requirements:** R1, R10

**Dependencies:** Unit 5

**Files:**
- Modify: `githooks/example/resources/example-githooks/build.mill` (Usage block)

**Approach:** Add a `./mill resolve git.tsa`-style Usage line (resolve-only; `exclusive = true` + `Evaluator` cannot execute under ExampleTester — memory-documented constraint). Usage block stays free of quotes/brackets/`for`.

**Test scenarios:**
- Integration: example workspace resolves the new command against the staged local artifact.

**Verification:** `./mill githooks.example.test` green.

## System-Wide Impact

- **Interaction graph:** `GitHooksModule` gains defs/commands (additive); `GitInstall` pre-push template changes affect every repo that reinstalls hooks — the advisory block must be inert on failure. Core is untouched in v1.
- **Error propagation:** fail-loud in CI, warn-and-continue in the hook, fail-safe (`tsa:show` + failed check) in the workflow. Signal failures floor at Show and are visible in the JSON.
- **State lifecycle risks:** label lifecycle in the workflow (stale-label removal, override respect, one-way auto-merge latch, per-PR concurrency). Ask-entry transition detection reconstructs previous state from existing labels — a small state machine in YAML with no test harness; keep it minimal and documented. Human deletion of a plain `tsa:*` label drifts until the next push — accepted.
- **API surface parity:** new public classes in githooks locked by MiMa at next release; deliberate surface review before tagging (votes flag, TsaContext fields, GitDiff record shape).
- **Integration coverage:** the Steward fixture + adversarial negatives (Unit 4) plus this repo's shadow-phase adoption are the end-to-end proof; ExampleTester covers surface resolution only.
- **Unchanged invariants:** existing hook behaviors untouched; `release`'s `ConventionalCommit` untouched; `core` untouched.

## Risks & Dependencies

| Risk | Mitigation |
|------|------------|
| Dep-only predicate too loose (supply-chain boundary) | Version-literal-only rule + adversarial negatives in Unit 4; shadow phase before the flag |
| Steward PRs fire no events (`GITHUB_TOKEN`) | Hard prerequisite documented: bot runs with App/PAT; this repo migrates its Steward workflow |
| Post-enable push merges before escalation lands | TSA check must be a required status check wherever the flag is on |
| Predicate self-reference: a PR fixing the predicate is verified by the old predicate | Accepted (base-code execution is the point); noted in docs |
| Default thresholds/fan-out make Ask the modal tier | `tsaFanOutThreshold` + docs/test-only exemption; shadow dry-run validates distribution |
| Privileged workflow assembled wrong by consumers | R19 ships complete YAML with constraints inline; actionlint in CI |
| MiMa locks a premature API | All new surface in githooks only; deliberate review before the release tag |
| In-flight uncommitted githooks changes collide | Branch for TSA after `fix/scalafix-classloader-leak` lands |
| jGit rename-detection subtleties | Fixture-repo tests pin behavior; threshold deferred to implementation |

## Documentation / Operational Notes

- Adopt `tsa.yml` in this repo first, label-only, as the shadow phase; review the tier distribution before enabling the auto-merge flag anywhere.
- Migrate this repo's Scala Steward setup to App/PAT credentials as part of the shadow phase (prerequisite for the flagship path).
- Docs must carry: token scoping/rotation, required-check instruction for flag-enabled repos, CODEOWNERS carve-out, base-code-only constraint, bot-identity pinning rationale.

## Sources & References

- **Origin document:** [docs/brainstorms/2026-07-29-tsa-pr-classification-requirements.md](../brainstorms/2026-07-29-tsa-pr-classification-requirements.md)
- Related code: `githooks/src/atn/mill/*.scala`, `core/src/atn/mill/GitRepo.scala`, `release/src/atn/mill/ConventionalCommit.scala`, `sonar/src/atn/mill/SonarScanner.scala`
- CI: `.github/workflows/ci.yml`, `.github/workflows/scala-steward.yml`
