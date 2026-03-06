package atn.mill

import sbt.testing.*

/**
 * Minimal standalone test runner for stryker4s mutation testing.
 *
 * This class is compiled separately at runtime and placed on the forked JVM's classpath alongside the test module's
 * classpath. It loads the sbt.testing.Framework, discovers and runs tests, then exits with code 0 (all pass) or 1 (any
 * failure).
 *
 * Usage: `java -cp <classpath> atn.mill.StrykerTestRunnerMain <framework-class> [test-class ...]`
 *
 * The ACTIVE_MUTATION system property activates a specific mutation when set.
 */
object StrykerTestRunnerMain:

  def main(args: Array[String]): Unit =
    if args.isEmpty then
      System.err.println("Usage: StrykerTestRunnerMain <framework-class> [test-class ...]")
      sys.exit(2)

    val frameworkClass = args(0)
    val testClassNames = args.drop(1)

    val cl = Thread.currentThread().getContextClassLoader

    // Load and instantiate the test framework
    val framework = Class
      .forName(frameworkClass, true, cl)
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[Framework]

    val runner = framework.runner(Array.empty, Array.empty, cl)

    // Build TaskDefs for each test class, using the first fingerprint from the framework
    val fingerprint = framework.fingerprints().head
    val taskDefs    = testClassNames.map(className => new TaskDef(className, fingerprint, false, Array(new SuiteSelector())))

    val tasks = runner.tasks(taskDefs)

    // Run all tasks and collect results
    var allPassed             = true
    val handler: EventHandler = (event: Event) =>
      if event.status() == Status.Failure || event.status() == Status.Error then allPassed = false

    val loggers = Array[Logger](new Logger:
      def ansiCodesSupported(): Boolean = false
      def error(msg: String): Unit      = System.err.println(msg)
      def warn(msg: String): Unit       = System.err.println(msg)
      def info(msg: String): Unit       = () // quiet for mutation testing
      def debug(msg: String): Unit      = ()
      def trace(t: Throwable): Unit     = t.printStackTrace(System.err))

    // Execute tasks, including any nested tasks returned by execute()
    def executeTasks(ts: Array[Task]): Unit =
      ts.foreach { task =>
        val nested = task.execute(handler, loggers)
        if nested.nonEmpty then executeTasks(nested)
      }

    executeTasks(tasks)
    runner.done()
    sys.exit(if allPassed then 0 else 1)
