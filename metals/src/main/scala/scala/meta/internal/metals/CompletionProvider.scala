package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextDocumentPositionParams
import scala.collection.concurrent.TrieMap
import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.LibraryManager
import scala.meta.internal.pc.ScalaPC
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolIndexer

class CompletionProvider(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    buffers: Buffers,
    indexer: SymbolIndexer
) {
  val libmanager = LibraryManager(workspace.toNIO)
  val searches = TrieMap.empty[BuildTargetIdentifier, ClasspathSearch]
  def withPC[T](
      params: TextDocumentPositionParams
  )(fn: (ScalaPC, Position) => T): Option[T] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    for {
      target <- buildTargets.inverseSources(path)
      scalac <- buildTargets.scalacOptions(target)
    } yield {
      val classpath = scalac.classpath.map(_.toNIO).toSeq
      val search = searches.getOrElseUpdate(
        target,
        ClasspathSearch.fromClasspath(classpath, _ => 0)
      )
      val pc = new ScalaPC(
        classpath,
        scalac.getOptions.asScala,
        indexer,
        search,
        Some(libmanager)
      )
      val input = path.toInputFromBuffers(buffers)
      val pos = params.getPosition.toMeta(input)
      val result = fn(pc, pos)
      pc.shutdown()
      result
    }
  }
  def completions(params: CompletionParams): Option[CompletionList] =
    withPC(params) { (pc, pos) =>
      pc.complete(pos.input.syntax, pos.input.text, pos.start)
    }
  def hover(params: TextDocumentPositionParams): Option[Hover] =
    withPC(params) { (pc, pos) =>
      pc.hover(pos.input.syntax, pos.input.text, pos.start)
    }
  def signatureHelp(params: TextDocumentPositionParams): Option[SignatureHelp] =
    withPC(params) { (pc, pos) =>
      pc.signatureHelp(pos.input.syntax, pos.input.text, pos.start)
    }
}
