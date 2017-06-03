package scala.tools.nsc

import java.io.{File, IOException}
import java.net.URL
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations.Mode._
import org.openjdk.jmh.annotations._

import scala.collection.JavaConverters._

@State(Scope.Benchmark)
class ScalacBenchmark {
  @Param(value = Array())
  var source: String = _

  @Param(value = Array(""))
  var extraArgs: String = _

  // This parameter is set by ScalacBenchmarkRunner / UploadingRunner based on the Scala version.
  // When running the benchmark directly the "latest" symlink is used.
  @Param(value = Array("latest"))
  var corpusVersion: String = _

  var depsClasspath: String = _

  def compileImpl(): Unit = {
    val compilerArgs =
      if (source.startsWith("@")) Array(source)
      else {
        import scala.collection.JavaConverters._
        val allFiles = Files.walk(findSourceDir, FileVisitOption.FOLLOW_LINKS).collect(Collectors.toList[Path]).asScala.toList
        allFiles.filter(f => {
          val name = f.getFileName.toString
          name.endsWith(".scala") || name.endsWith(".java")
        }).map(_.toAbsolutePath.normalize.toString).toArray
      }

    val args = Array(
      "-usejavacp",
      "-d", tempDir.getAbsolutePath,
      "-nowarn"
    ) ++ compilerArgs

    dotty.tools.dotc.Main.process(args)
  }

  private var tempDir: File = null

  // Executed once per fork
  @Setup(Level.Trial) def initTemp(): Unit = {
    val tempFile = java.io.File.createTempFile("output", "")
    tempFile.delete()
    tempFile.mkdir()
    tempDir = tempFile
  }
  @TearDown(Level.Trial) def clearTemp(): Unit = {
    val directory = tempDir.toPath
    Files.walkFileTree(directory, new SimpleFileVisitor[Path]() {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.delete(file)
        FileVisitResult.CONTINUE
      }
      override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
        Files.delete(dir)
        FileVisitResult.CONTINUE
      }
    })
  }

  private def corpusSourcePath = Paths.get(s"../corpus/$source/$corpusVersion")

  @Setup(Level.Trial) def initDepsClasspath(): Unit = {
    val depsDir = Paths.get(ConfigFactory.load.getString("deps.localdir"))
    val depsFile = corpusSourcePath.resolve("deps.txt")
    if (Files.exists(depsFile)) {
      val res = new StringBuilder()
      for (depUrlString <- Files.lines(depsFile).iterator().asScala) {
        val depUrl = new URL(depUrlString)
        val filename = Paths.get(depUrl.getPath).getFileName.toString
        val depFile = depsDir.resolve(filename)
        // TODO: check hash if file exists, or after downloading
        if (!Files.exists(depFile)) {
          if (!Files.exists(depsDir)) Files.createDirectories(depsDir)
          val in = depUrl.openStream
          Files.copy(in, depFile, StandardCopyOption.REPLACE_EXISTING)
          in.close()
        }
        if (res.nonEmpty) res.append(File.pathSeparator)
        res.append(depFile.toAbsolutePath.normalize.toString)
      }
      depsClasspath = res.toString
    }
  }

  private def findSourceDir: Path = {
    val path = corpusSourcePath
    if (Files.exists(path)) path
    else Paths.get(source)
  }
}

// JMH-independent entry point to run the code in the benchmark, for debugging or
// using external profilers.
object ScalacBenchmarkStandalone {
  def main(args: Array[String]): Unit = {
    val bench = new ScalacBenchmark
    bench.source = args(0)
    val iterations = args(1).toInt
    bench.initTemp()
    var i = 0
    while (i < iterations) {
      bench.compileImpl()
      i += 1
    }
    bench.clearTemp()
  }
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
// TODO -Xbatch reduces fork-to-fork variance, but incurs 5s -> 30s slowdown
@Fork(value = 16, jvmArgs = Array("-XX:CICompilerCount=2", "-Xms2G", "-Xmx2G"))
class ColdScalacBenchmark extends ScalacBenchmark {
  @Benchmark
  def compile(): Unit = compileImpl()
}

@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1, time = 30, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class WarmScalacBenchmark extends ScalacBenchmark {
  @Benchmark
  def compile(): Unit = compileImpl()
}

@BenchmarkMode(Array(SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class HotScalacBenchmark extends ScalacBenchmark {
  @Benchmark
  def compile(): Unit = compileImpl()
}
