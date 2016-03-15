package org.broadinstitute.hail.driver

import org.broadinstitute.hail.Utils._
import org.broadinstitute.hail.annotations._
import org.kohsuke.args4j.{Option => Args4jOption}
import scala.StringBuilder
import scala.collection.mutable

object ShowAnnotations extends Command {

  class Options extends BaseOptions {
    @Args4jOption(name = "-o", aliases = Array("--output"), usage = "Output file")
    var output: String = _
  }

  def newOptions = new Options

  def name = "showannotations"

  def description = "Shows the signatures for all annotations currently stored in the dataset"

  override def supportsMultiallelic = true

  def run(state: State, options: Options): State = {
    val vds = state.vds

    if (vds == null)
      fatal("showannotations requires a non-null variant dataset, import or read one first")

    val sb = new StringBuilder()
    sb.append("Sample annotations:")
    sb.append("\n")
    sb.append(vds.saSignature.printSchema("sa", 2, "sa"))
    sb.append("\n")
    sb.append("Variant annotations:")
    sb.append(vds.vaSignature.printSchema("va", 2, "va"))
    val result = sb.result()

    options.output match {
      case null => println(result)
      case out => writeTextFile(out, vds.sparkContext.hadoopConfiguration) { dos =>
        dos.write(result)
      }
    }

    state
  }
}