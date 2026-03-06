# CLAUDE.md - mill-plugins

## Build Commands

- `./mill __.compile` - Compile all modules
- `./mill __.test` - Run all tests
- `./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll` - Check formatting
- `./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll` - Format all code

Note: These are NOT meta-level commands. The plugins ARE the meta-level code, compiled as regular Mill modules here.

## Architecture

Each plugin is a separate Mill module producing one JAR. All source files are in `package atn.mill` (flat, no sub-packages).

### Constraints

- **Plugins MUST NOT depend on each other** - shared code goes in `core`
- All modules depend on `core` transitively
- Mill API is a `compileIvyDeps` (provided scope) dependency
- Never use `sys.env` directly - accept config as overridable `def`s
- Never embed credentials or URLs

## Development Workflow

- TDD required: write failing test first
- No `var`, no `return`, no mutable state (except where legacy requires it)
- Conventional commits required
- All public trait defs should have scaladoc

## Testing

- Framework: utest
- Property testing: ScalaCheck
- Test timeout: 30 seconds
