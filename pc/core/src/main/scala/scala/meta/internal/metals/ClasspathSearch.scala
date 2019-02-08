package scala.meta.internal.metals

import java.nio.file.Path
import java.util
import java.util.Comparator
import scala.collection.concurrent.TrieMap
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

class ClasspathSearch(
    map: collection.Map[String, CompressedPackageIndex],
    packagePriority: String => Int
) extends SymbolSearch {
  private val byReferenceThenAlphabeticalComparator = new Comparator[String] {
    override def compare(a: String, b: String): Int = {
      val byReference = -Integer.compare(packagePriority(a), packagePriority(b))
      if (byReference != 0) byReference
      else a.compare(b)
    }
  }

  override def search(query: String, visitor: SymbolSearchVisitor): Unit = {
    search(
      WorkspaceSymbolQuery.exact(query),
      pkg => visitor.preVisitPackage(pkg),
      () => visitor.isCancelled
    )
  }

  private def packagesSortedByReferences(): Array[String] = {
    val packages = map.keys.toArray
    util.Arrays.sort(packages, byReferenceThenAlphabeticalComparator)
    packages
  }

  def search(query: String): Iterator[Classfile] = {
    search(WorkspaceSymbolQuery.exact(query), _ => true, () => false)
  }

  def search(
      query: WorkspaceSymbolQuery,
      visitPackage: String => Boolean,
      isCancelled: () => Boolean
  ): Iterator[Classfile] = {
    val packages = packagesSortedByReferences()
    for {
      pkg <- packages.iterator
      if visitPackage(pkg)
      if !isCancelled()
      compressed = map(pkg)
      if query.matches(compressed.bloom)
      member <- compressed.members
      if member.endsWith(".class")
      symbol = new ConcatSequence(pkg, member)
      isMatch = query.matches(symbol)
      if isMatch
    } yield Classfile(pkg, member)
  }

}

object ClasspathSearch {
  def empty: ClasspathSearch = new ClasspathSearch(Map.empty, _ => 0)
  def fromPackages(
      packages: PackageIndex,
      packagePriority: String => Int
  ): ClasspathSearch = {
    val map = TrieMap.empty[String, CompressedPackageIndex]
    map ++= CompressedPackageIndex.fromPackages(packages)
    new ClasspathSearch(map, packagePriority)
  }
  def fromClasspath(
      classpath: Seq[Path],
      packagePriority: String => Int
  ): ClasspathSearch = {
    val packages = new PackageIndex
    packages.visitBootClasspath()
    classpath.foreach { path =>
      packages.visit(AbsolutePath(path))
    }
    fromPackages(packages, packagePriority)
  }
}