# mill-plugins

Custom [Mill](https://mill-build.org/) build plugins for Scala projects.

## Plugins

| Module | Artifact | Description | Example |
|--------|----------|-------------|---------|
| stryker4s | `mill-stryker4s_mill1` | Mutation testing via Stryker4s | [example](stryker4s/example/resources/example-stryker4s/build.mill) |
| scalafix | `mill-scalafix_mill1` | Scalafix wrapper for Mill | [example](scalafix/example/resources/example-scalafix/build.mill) |
| docs | `mill-docs_mill1` | Scaladoc site generation | [example](docs/example/resources/example-docs/build.mill) |
| githooks | `mill-githooks_mill1` | Git hooks (formatting, testing, commit validation) | [example](githooks/example/resources/example-githooks/build.mill) |
| sonar | `mill-sonar_mill1` | SonarQube scanner integration | [example](sonar/example/resources/example-sonar/build.mill) |
| devx | `mill-devx_mill1` | Developer experience (CodeScene, Port.io) | [example](devx/example/resources/example-devx/build.mill) |

## Usage

Add the plugin(s) you want to your `mill-build/build.mill`:

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
