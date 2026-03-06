package atn.mill

import utest._

object DocTransformerTest extends TestSuite:

  val tests = Tests:

    test("titleFromFilename") {
      test("README becomes project name") {
        val result = DocTransformer.titleFromFilename("README.md", "bi-services")
        assert(result == "bi-services")
      }
      test("TOPICS becomes Topics") {
        val result = DocTransformer.titleFromFilename("TOPICS.MD", "bi-services")
        assert(result == "Topics")
      }
      test("kebab-case becomes title case") {
        val result = DocTransformer.titleFromFilename("getting-started.md", "bi-services")
        assert(result == "Getting Started")
      }
      test("snake_case becomes title case") {
        val result = DocTransformer.titleFromFilename("my_document.md", "bi-services")
        assert(result == "My Document")
      }
    }

    test("targetFilename") {
      test("README becomes index.md") {
        val result = DocTransformer.targetFilename("README.md")
        assert(result == "index.md")
      }
      test("DEVCONTAINERS becomes devcontainers.md") {
        val result = DocTransformer.targetFilename("DEVCONTAINERS.MD")
        assert(result == "devcontainers.md")
      }
    }

    test("slugify") {
      test("title to filename") {
        assert(DocTransformer.slugify("Kafka Topics") == "kafka-topics")
      }
      test("strips special characters") {
        assert(DocTransformer.slugify("Getting Started!") == "getting-started")
      }
      test("collapses multiple dashes") {
        assert(DocTransformer.slugify("foo  --  bar") == "foo-bar")
      }
    }

    test("FrontMatter.render") {
      test("generates YAML frontmatter block") {
        val result = FrontMatter("title" -> "My Page").render
        assert(result == "---\ntitle: My Page\n---\n\n")
      }
      test("handles multiple fields sorted alphabetically") {
        val result = FrontMatter("title" -> "My Page", "sidebar_position" -> "3").render
        assert(result.contains("sidebar_position: 3"))
        assert(result.contains("title: My Page"))
        assert(result.startsWith("---\n"))
        assert(result.contains("\n---\n"))
      }
    }

    test("parseSplitMarker") {
      test("extracts YAML fields") {
        val line   = "<!-- split: title: Kafka Topics -->"
        val result = SplitMarker.parse(line)
        assert(result == SplitMarker.Marker(FrontMatter("title" -> "Kafka Topics")))
      }
      test("handles multiple fields") {
        val line   = "<!-- split: title: My Page, sidebar_position: 3 -->"
        val result = SplitMarker.parse(line)
        assert(result == SplitMarker.Marker(FrontMatter("title" -> "My Page", "sidebar_position" -> "3")))
      }
      test("returns NoMarker for non-marker lines") {
        val line   = "## Regular heading"
        val result = SplitMarker.parse(line)
        assert(result == SplitMarker.NoMarker)
      }
      test("returns NoMarker for regular HTML comments") {
        val line   = "<!-- this is a regular comment -->"
        val result = SplitMarker.parse(line)
        assert(result == SplitMarker.NoMarker)
      }
    }

    test("splitDocument") {
      test("no markers returns single section") {
        val content  = "# Title\n\nSome content\n"
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

    test("extractImagePaths") {
      test("finds markdown image references") {
        val content = "![alt](images/foo.png)\ntext\n![bar](./diagrams/arch.svg)"
        val result  = ImageAdjuster.extractImagePaths(content)
        assert(result == Seq("images/foo.png", "./diagrams/arch.svg"))
      }
      test("ignores external URLs") {
        val content = "![alt](https://example.com/img.png)"
        val result  = ImageAdjuster.extractImagePaths(content)
        assert(result.isEmpty)
      }
      test("ignores absolute paths") {
        val content = "![alt](/absolute/path.png)"
        val result  = ImageAdjuster.extractImagePaths(content)
        assert(result.isEmpty)
      }
    }

    test("transform") {
      test("simple file without splits") {
        val tmp       = os.temp.dir()
        val sourceDir = tmp / "project"
        val targetDir = tmp / "staged" / "_docs"
        os.makeDir.all(sourceDir)
        os.makeDir.all(targetDir)
        os.write(sourceDir / "TOPICS.md", "# Kafka Topics\n\nSome content about topics.\n")

        val result =
          DocTransformer.transform(source = sourceDir / "TOPICS.md", projectName = "bi-services", targetDir = targetDir)

        assert(result.files == Seq(targetDir / "topics.md"))
        assert(result.warnings.isEmpty)
        val content = os.read(targetDir / "topics.md")
        assert(content.startsWith("---\ntitle: Topics\n---\n"))
        assert(content.contains("# Kafka Topics"))
        assert(content.contains("Some content about topics."))
      }

      test("README becomes index.md with project name title") {
        val tmp       = os.temp.dir()
        val sourceDir = tmp / "project"
        val targetDir = tmp / "staged" / "_docs"
        os.makeDir.all(sourceDir)
        os.makeDir.all(targetDir)
        os.write(sourceDir / "README.md", "# My Project\n\nWelcome.\n")

        val result =
          DocTransformer.transform(source = sourceDir / "README.md", projectName = "bi-services", targetDir = targetDir)

        assert(result.files == Seq(targetDir / "index.md"))
        val content = os.read(targetDir / "index.md")
        assert(content.startsWith("---\ntitle: bi-services\n---\n"))
      }

      test("file with split markers produces multiple files") {
        val tmp       = os.temp.dir()
        val sourceDir = tmp / "project"
        val targetDir = tmp / "staged" / "_docs"
        os.makeDir.all(sourceDir)
        os.makeDir.all(targetDir)
        os.write(
          sourceDir / "README.md",
          "# Main\n\nIntro\n\n<!-- split: title: Getting Started -->\n\n# Getting Started\n\nSteps here.\n"
        )

        val result =
          DocTransformer.transform(source = sourceDir / "README.md", projectName = "bi-services", targetDir = targetDir)

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
        val tmp       = os.temp.dir()
        val sourceDir = tmp / "project"
        val targetDir = tmp / "staged" / "_docs"
        os.makeDir.all(sourceDir / "images")
        os.makeDir.all(targetDir)
        os.write(sourceDir / "images" / "arch.png", "fake-image-data")
        os.write(sourceDir / "DOC.md", "# Doc\n\n![arch](images/arch.png)\n")

        val result = DocTransformer.transform(source = sourceDir / "DOC.md", projectName = "test", targetDir = targetDir)

        assert(result.warnings.isEmpty)
        assert(os.exists(targetDir / "images" / "arch.png"))
        assert(os.read(targetDir / "images" / "arch.png") == "fake-image-data")
      }

      test("returns warnings for missing images") {
        val tmp       = os.temp.dir()
        val sourceDir = tmp / "project"
        val targetDir = tmp / "staged" / "_docs"
        os.makeDir.all(sourceDir)
        os.makeDir.all(targetDir)
        os.write(sourceDir / "DOC.md", "# Doc\n\n![missing](images/nope.png)\n")

        val result = DocTransformer.transform(source = sourceDir / "DOC.md", projectName = "test", targetDir = targetDir)

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

        val ex =
          try
            DocTransformer.transform(tmp / "NONEXISTENT.md", "test", targetDir)
            sys.error("Expected IllegalArgumentException")
          catch case e: IllegalArgumentException => e
        assert(ex.getMessage.contains("Static doc source not found"))
      }

      test("split marker without title throws") {
        val tmp       = os.temp.dir()
        val sourceDir = tmp / "project"
        val targetDir = tmp / "_docs"
        os.makeDir.all(sourceDir)
        os.makeDir.all(targetDir)
        os.write(sourceDir / "BAD.md", "# Main\n\n<!-- split: sidebar_position: 3 -->\n\nContent\n")

        val ex =
          try
            DocTransformer.transform(sourceDir / "BAD.md", "test", targetDir)
            sys.error("Expected IllegalArgumentException")
          catch case e: IllegalArgumentException => e
        assert(ex.getMessage.contains("missing 'title'"))
      }
    }
