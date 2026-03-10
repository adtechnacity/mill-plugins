# Changelog

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
