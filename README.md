# mill-plugins

Custom [Mill](https://mill-build.org/) build plugins for Scala projects.

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| core | `mill-core_mill1` | Shared utilities (git helpers, Result extensions, WorkDone ADT) |
| stryker4s | `mill-stryker4s_mill1` | Mutation testing via Stryker4s |
| scalafix | `mill-scalafix_mill1` | Scalafix wrapper for Mill |
| docs | `mill-docs_mill1` | Scaladoc site generation |
| githooks | `mill-githooks_mill1` | Git hooks (formatting, testing, commit validation) |
| sonar | `mill-sonar_mill1` | SonarQube scanner integration |
| devx | `mill-devx_mill1` | Developer experience (CodeScene, Port.io) |

## Usage

Add to your `mill-build/build.mill`:

```scala
def mvnDeps = Seq(
  mvn"com.adtechnacity::mill-stryker4s_mill1:0.1.0",
  mvn"com.adtechnacity::mill-githooks_mill1:0.1.0",
  // ... etc
)
```

All plugins are in `package atn.mill`, so `import atn.mill.*` gives access to all traits.

## Building

```bash
./mill __.compile    # Compile all modules
./mill __.test       # Run all tests
```

## License

Apache 2.0
