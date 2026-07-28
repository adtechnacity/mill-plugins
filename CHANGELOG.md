# Changelog

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
