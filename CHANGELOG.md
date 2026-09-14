# Changelog

## [1.2.0] - 2026-09-15

### Fixed

- **release**: honour custom tagPrefix and the BREAKING CHANGE footer (#25)

### Changed

- **scalafix**: reformat FakeScalafixArguments with scalafmt 3.11.5 (#26)

### Dependencies

- **stryker4s**: stryker4s-core and stryker4s-command-runner 0.21.0 → 1.1.1, forking the renamed `stryker4s-testrunner`
  (`TestRunnerMain`); mutation results can differ from 0.21.0, and stryker4s 1.x targets Java 17 bytecode (#28)
- **stryker4s**: fs2-core 3.13.0 → 3.14.0 (#28)
- **pulumi**: pulumi 1.13.2 → 1.37.0 (#7, #28)
- **sonar**: sonar-scanner-java-library 4.1.1.1633 → 4.1.2.1663, logback-classic 1.5.38 → 1.6.3 (#7, #28)
- **scalafix**: scalafix-interfaces 0.14.7 → 0.14.9 (#28)
- **core,githooks**: org.eclipse.jgit 7.7.1 → 7.8.0 (#28)
- built against Mill 1.1.9 (was 1.1.6) (#28)

### Other

- share test support across modules, deterministic listModules (#27)
- **devx**: raise statement coverage to 99% and kill 22 mutants (#20)
- **core,docs,pulumi**: raise coverage and kill mutants (#24)
- **scalafix**: add unit tests for scalafix and sonar, join mutation (#23)
- **stryker4s**: raise statement coverage to 98% and kill 42 mutants (#22)
- mutate the whole module when a PR changes only its tests (#21)
- **githooks**: raise statement coverage to 98% and kill 51 mutants (#19)
- **release**: raise statement coverage to 100% and kill 13 mutants (#18)
- stryker4s 1.1.1, re-enable MethodExpression and cpd StringLiteral (#17)
- set next development version 1.1.2-SNAPSHOT

## [1.1.1] - 2026-09-09

### Fixed

- **stryker4s**: upgrade to stryker4s-core 0.21.0 so compile-error rollback matches the compiled text (#16)

### Other

- explicit empty states in the quality report and a race-safe sticky-comment post
- bump the stryker4s pin to 1.1.0 and activate cpd mutation and the file-level delta
- set next development version 1.1.1-SNAPSHOT

## [1.1.0] - 2026-09-08

### Added

- **stryker4s**: strykerIncludedFiles for file-level mutation scope
- **stryker4s**: per-mutant compiler error reporting

### Fixed

- **stryker4s**: keep testrunner logs out of the stryker tmp dir
- **stryker4s**: forward forkEnv and forkArgs to the testrunner

### Changed

- remove the duplications CPD flagged and use the default CPD thresholds

### Other

- dogfood mill-cpd 1.0.0 and post a CPD section on pull requests
- coverage, mutation-delta and example runs with a sticky PR quality report
- set next development version 1.0.1-SNAPSHOT

## [1.0.0] - 2026-09-08

### Breaking Changes

- **docs**: reference the root build through ModuleRef so wildcard selectors resolve

### Added

- **cpd**: workspace-wide CPD with warning/error token thresholds

### Fixed

- **examples**: restore ExampleTester Usage blocks so example tests execute
- **docs**: reference the root build through ModuleRef so wildcard selectors resolve

## [0.9.0] - 2026-09-04

### Added

- **stryker4s**: `strykerExcludedFiles` — per-file mutation excludes, passed through to stryker4s's `mutate` config
  as `!`-prefixed negative globs. Lets a single file that cannot produce a compilable mutant be skipped without
  dropping a mutator across the whole module, which `strykerExcludedMutations` would.

### Fixed

- **stryker4s**: Scala 2 modules could not be mutation-tested at all. The runner hardcoded the Scala 3 compiler
  artifact (`scala3-compiler_3`) and its main class (`dotty.tools.dotc.Main`), so a 2.13 module asked coursier for a
  non-existent `scala3-compiler_3:2.13.16`. Both are now selected from the module's `scalaVersion`.

## [0.8.0] - 2026-07-29

### Added

- **pulumi**: infrastructure as Scala code via the Pulumi Automation API
- **githooks**: selective pre-commit checks with configurable snapshot

### Other

- TSA (tell/show/ask) PR classification requirements and plan
- set next development version 0.7.1-SNAPSHOT

## [0.7.0] - 2026-07-28

Re-release of 0.6.1 under the correct minor version: it introduced a new
feature (stryker4s coverage-based test selection). Identical to 0.6.1.

## [0.6.1] - 2026-07-28

### Added

- **stryker4s**: coverage-based test selection via the upstream sbt-testrunner

### Fixed

- **ci**: accept intentional MiMa breaks from the stryker4s runner rewrite
- **ci**: keep Mill wrapper out of the dir scala-steward-action clobbers
- **ci**: put Mill wrapper on PATH so scala-steward can run mill
- **scalafix**: cache tool classloader to stop compressed-class-space OOM (#1)

### Other

- dispatch CI on Scala Steward update branches
- set next development version 0.6.1-SNAPSHOT

## [0.6.0] - 2026-06-28

### Added

- **githooks**: add prePushExtraCommands for pre-push gates

### Fixed

- **scalafix**: cache tool classloader to stop compressed-class-space OOM

### Other

- set next development version 0.5.1-SNAPSHOT

## [0.5.0] - 2026-06-20

### Fixed

- **githooks**: make pre-push hook fail on a failing test run

### Other

- set next development version 0.4.1-SNAPSHOT

## [0.4.0] - 2026-05-14

### Added

- **scalafix**: support in-repo rule modules via scalafixToolModules

### Changed

- **scalafix**: tighten ScalafixSupport API, dedupe config lookup, cover failure path

### Other

- **deps**: bump Mill, Scala, upickle, fs2/stryker4s, sonar-scanner, mainargs, requests
- set next development version 0.3.4-SNAPSHOT

## [0.3.3] - 2026-04-02

### Fixed

- **stryker4s**: filter -Yexplicit-nulls from scalacOptions during mutation

### Other

- set next development version 0.3.3-SNAPSHOT

## [0.3.2] - 2026-04-02

### Added

- **scalafix**: add overridable scalafixMvnDeps and bump scalafix to 0.14.6
- Stryker4sReport now defaults to runAll

### Fixed

- address mima warning

### Other

- set next development version 0.3.2-SNAPSHOT

## [0.3.1] - 2026-03-10

### Added

- add runAll command to Stryker4sReport for cross-module mutation testing

### Changed

- remove duplicate scaladoc on latestReportDir
- apply scalafmt formatting

### Other

- update MiMa baseline to v0.3.0
- set next development version 0.3.1-SNAPSHOT

## [0.3.0] - 2026-03-10

### Added

- add rel module for release automation

### Fixed

- stryker4s mutation testing and report aggregation
- stryker4s was generating reports to the target/ folder

### Changed

- scalafix tasks don't need an evaluator
- move WorkDone to githooks, improve CodeScene docs, fix stryker4s reports

### Other

- set next development version 0.2.3-SNAPSHOT

## [0.2.2] - 2026-03-10

### Added

- add MiMa binary compatibility checks and reinstate release plugin
- **ci**: add Scala Steward and dependency caching

### Other

- set next development version 0.2.2-SNAPSHOT

## [0.2.1] - 2026-03-10

### Added

- **release**: add auto-release task that infers bump from commits

### Fixed

- **release**: peel annotated tags to commit for log range
- **ci**: switch to SonatypeCentralPublishModule for Maven Central
- remove release plugin meta-build dependency for CI bootstrap

### Other

- document release workflow and update CI to trigger on tags

## [0.2.0] - 2026-03-09

### Added

- **release**: add patch/minor/major release automation tasks
- add release plugin and fix example test plugin dependencies
- include claude settings in repo
- add Example Tests for all 6 plugins
- switch from GitHub Packages to Sonatype publishing

### Changed

- use published release plugin instead of inline copy
