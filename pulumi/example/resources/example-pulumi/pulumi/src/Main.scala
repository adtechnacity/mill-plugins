package infra

import com.pulumi.Pulumi
import com.pulumi.core.Output

object Main {
  def main(args: Array[String]): Unit =
    Pulumi.run { ctx =>
      ctx.`export`("greeting", Output.of("hello from mill"))
    }
}
