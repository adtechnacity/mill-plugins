package atn.mill

import utest._

object DocTransformerTest extends TestSuite:

  val tests = Tests:

    test("titleFromFilename - the .MD suffix goes, words split on - or _ and are title-cased") {
      val titles =
        Seq("TOPICS.MD" -> "Topics", "getting-started.md" -> "Getting Started", "my_document.md" -> "My Document")
      titles.foreach((filename, title) => assert(DocTransformer.titleFromFilename(filename, "bi-services") == title))
    }

    test("targetFilename - lower-cased with the suffix normalised to .md") {
      assert(DocTransformer.targetFilename("DEVCONTAINERS.MD") == "devcontainers.md")
    }

    test("slugify - strips special characters and collapses runs of spaces and dashes") {
      assert(DocTransformer.slugify("Getting Started!") == "getting-started")
      assert(DocTransformer.slugify("foo  --  bar") == "foo-bar")
    }

    test("FrontMatter") {
      test("render - a YAML block with the fields sorted by key") {
        assert(FrontMatter("title" -> "My Page").render == "---\ntitle: My Page\n---\n\n")
        assert(
          FrontMatter("title" -> "My Page", "sidebar_position" -> "3").render ==
            "---\nsidebar_position: 3\ntitle: My Page\n---\n\n"
        )
      }
      test("fromMap keeps the given fields") {
        val fields = Map("title" -> "My Page", "sidebar_position" -> "2")
        assert(FrontMatter.fromMap(fields).fields == fields)
      }
      test("isEmpty only for the empty frontmatter") {
        assert(FrontMatter.empty.isEmpty)
        assert(!FrontMatter("title" -> "My Page").isEmpty)
      }
    }

    test("parseSplitMarker - fields are split on commas; only split comments are markers") {
      val marker = "<!-- split: title: My Page, sidebar_position: 3 -->"
      assert(SplitMarker.parse(marker) == SplitMarker.Marker(FrontMatter("title" -> "My Page", "sidebar_position" -> "3")))
      assert(SplitMarker.parse("<!-- this is a regular comment -->") == SplitMarker.NoMarker)
    }

    test("splitDocument") {
      test("no markers returns the content verbatim as a single section") {
        // No trailing newline: the pass-through must not re-join the lines.
        val content  = "# Title\n\nSome content"
        val sections = SplitMarker.splitDocument(content).toVector
        assert(sections.length == 1)
        assert(sections.head._1 == FrontMatter.empty)
        assert(sections.head._2 == content)
      }
      test("one marker splits into two sections") {
        val content  =
          "# Main Page\n\nIntro content\n\n" +
            "<!-- split: title: Second Page -->\n\n" +
            "# Second Page\n\nSecond content\n"
        val sections = SplitMarker.splitDocument(content).toVector
        assert(sections.length == 2)
        assert(sections(0)._1 == FrontMatter.empty)
        assert(sections(0)._2.contains("Main Page"))
        assert(sections(1)._1 == FrontMatter("title" -> "Second Page"))
        assert(sections(1)._2.contains("Second content"))
      }
    }

    test("extractImagePaths - relative references in order; URLs and absolute paths are left alone") {
      val content = "![alt](images/foo.png)\ntext\n![bar](./diagrams/arch.svg)"
      assert(ImageAdjuster.extractImagePaths(content) == Seq("images/foo.png", "./diagrams/arch.svg"))
      assert(ImageAdjuster.extractImagePaths("![alt](https://example.com/img.png)").isEmpty)
      assert(ImageAdjuster.extractImagePaths("![alt](/absolute/path.png)").isEmpty)
    }

    test("transform") {
      test("simple file without splits") {
        val (result, targetDir) = DocFixtures.transformed("TOPICS.md", "# Kafka Topics\n\nSome content about topics.\n")

        assert(result.files == Seq(targetDir / "topics.md"))
        assert(result.warnings.isEmpty)
        val content = os.read(targetDir / "topics.md")
        assert(content.startsWith("---\ntitle: Topics\n---\n"))
        assert(content.contains("# Kafka Topics"))
        assert(content.contains("Some content about topics."))
      }

      test("file with split markers produces multiple files") {
        val (result, targetDir) = DocFixtures.transformed(
          "README.md",
          "# Main\n\nIntro\n\n<!-- split: title: Getting Started -->\n\n# Getting Started\n\nSteps here.\n"
        )

        assert(result.files.length == 2)
        assert(result.files.contains(targetDir / "index.md"))
        assert(result.files.contains(targetDir / "getting-started.md"))
        val main  = os.read(targetDir / "index.md")
        assert(main.contains("title: bi-services"))
        assert(main.contains("Intro"))
        val split = os.read(targetDir / "getting-started.md")
        assert(split.contains("title: Getting Started"))
        assert(split.contains("Steps here."))
      }

      test("copies referenced images") {
        val (sourceDir, targetDir) = DocFixtures.projectDirs()
        os.write(sourceDir / "images" / "arch.png", "fake-image-data", createFolders = true)
        val result                 =
          DocFixtures.transform(sourceDir, targetDir, "DOC.md", "# Doc\n\n![arch](images/arch.png)\n", "test")

        assert(result.warnings.isEmpty)
        assert(os.exists(targetDir / "images" / "arch.png"))
        assert(os.read(targetDir / "images" / "arch.png") == "fake-image-data")
      }

      test("returns warnings for missing images") {
        val (result, targetDir) = DocFixtures.transformed("DOC.md", "# Doc\n\n![missing](images/nope.png)\n", "test")

        assert(result.files.nonEmpty)
        assert(result.warnings.length == 1)
        assert(result.warnings.head.contains("images/nope.png"))
      }
    }

    test("errors") {
      test("missing source file throws with clear message") {
        val tmp       = os.temp.dir()
        val targetDir = tmp / "_docs"
        os.makeDir.all(targetDir)

        val ex = DocFixtures.transformFailure(tmp / "NONEXISTENT.md", targetDir)
        assert(ex.getMessage.contains("Static doc source not found"))
      }

      test("split marker without title throws") {
        val (sourceDir, targetDir) = DocFixtures.projectDirs(target = os.sub / "_docs")
        os.write(sourceDir / "BAD.md", "# Main\n\n<!-- split: sidebar_position: 3 -->\n\nContent\n")

        val ex = DocFixtures.transformFailure(sourceDir / "BAD.md", targetDir)
        assert(ex.getMessage.contains("missing 'title'"))
      }
    }

/** Scratch project layouts for the `DocTransformer.transform` tests. */
private object DocFixtures:

  /** A fresh temp dir with a `project` source dir and a `target` dir, both created: (sourceDir, targetDir). */
  def projectDirs(target: os.SubPath = os.sub / "staged" / "_docs"): (os.Path, os.Path) =
    val tmp       = os.temp.dir()
    val sourceDir = tmp / "project"
    val targetDir = tmp / target
    os.makeDir.all(sourceDir)
    os.makeDir.all(targetDir)
    (sourceDir, targetDir)

  /** Writes `content` as `name` into `sourceDir` and transforms it into `targetDir`. */
  def transform(sourceDir: os.Path, targetDir: os.Path, name: String, content: String, projectName: String) =
    os.write(sourceDir / name, content)
    DocTransformer.transform(source = sourceDir / name, projectName = projectName, targetDir = targetDir)

  /** [[transform]] in a fresh project: (result, targetDir). */
  def transformed(name: String, content: String, projectName: String = "bi-services") =
    val (sourceDir, targetDir) = projectDirs()
    (transform(sourceDir, targetDir, name, content, projectName), targetDir)

  /** The `IllegalArgumentException` transforming `source` must throw. */
  def transformFailure(source: os.Path, targetDir: os.Path): IllegalArgumentException =
    try
      DocTransformer.transform(source, "test", targetDir)
      sys.error("Expected IllegalArgumentException")
    catch case e: IllegalArgumentException => e
