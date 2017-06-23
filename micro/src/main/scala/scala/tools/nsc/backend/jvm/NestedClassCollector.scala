package scala.tools.nsc.backend.jvm

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.Mode.Throughput
import org.openjdk.jmh.annotations._

import scala.tools.nsc.backend.jvm.BTypes.InternalName
import scala.tools.nsc.backend.jvm.analysis.BackendUtils.NestedClassesCollector

class Collector extends NestedClassesCollector[String] {
  override def declaredNestedClasses(internalName: InternalName): List[String] = Nil
  override def getClassIfNested(internalName: InternalName): Option[String] = Some(internalName)
  def raiseError(msg: String, sig: String, e: Option[Throwable]): Unit =
    throw e.getOrElse(new Exception(msg + " " + sig))
}

@BenchmarkMode(Array(Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2)
@State(Scope.Thread)
class NestedClassCollector {
  var c = new Collector

  @Benchmark
  def rtJar(): Unit = {
    import java.nio.file._
    import scala.collection.JavaConverters._
//    val zipfile = Paths.get("/Library/Java/JavaVirtualMachines/jdk1.8.0_131.jdk/Contents/Home/jre/lib/rt.jar")
    val zipfile = Paths.get("/Users/luc/.ivy2/local/org.scala-lang/scala-library/2.12.3-bin-746f0b3/jars/scala-library.jar")
    val fs = FileSystems.newFileSystem(zipfile, null)
    val root = fs.getRootDirectories.iterator().next()
    val contents = Files.walk(root).iterator().asScala.toList
    for (f <- contents if Files.isRegularFile(f) && f.getFileName.toString.endsWith(".class")) {
      val classNode = AsmUtils.classFromBytes(Files.readAllBytes(f))
      c.visitClassSignature(classNode.signature)
      classNode.methods.iterator().asScala.map(_.signature).foreach(c.visitMethodSignature)
      classNode.fields.iterator().asScala.map(_.signature).foreach(c.visitFieldSignature)
    }
  }
}
