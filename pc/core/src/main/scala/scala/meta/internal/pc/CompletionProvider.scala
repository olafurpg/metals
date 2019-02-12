package scala.meta.internal.pc

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.meta.internal.metals.Fuzzy
import scala.meta.pc.CompletionItems
import scala.meta.pc.CompletionItems.LookupKind
import scala.meta.pc.OffsetParams
import scala.meta.pc.SymbolSearch
import scala.util.control.NonFatal

class CompletionProvider(
    val compiler: PresentationCompiler,
    params: OffsetParams
) {
  import compiler._

  val maxWorkspaceSymbolResults = 10

  def completions(): CompletionItems = {
    val unit = addCompilationUnit(
      code = params.text,
      filename = params.filename,
      cursor = Some(params.offset)
    )
    val position = unit.position(params.offset)
    val shortenedNames = new ShortenedNames()
    val (qual, kind, i) = safeCompletionsAt(position)
    def methodString(method: MethodSymbol, info: Type): String = {
      val printer = new SignaturePrinter(method, shortenedNames, info)
      var i = 0
      val paramss = info.typeParams match {
        case Nil => info.paramss
        case tparams => tparams :: info.paramss
      }
      val params = paramss.iterator.map { params =>
        val labels = params.iterator.map { param =>
          val result = printer.paramLabel(param, i)
          i += 1
          result
        }
        labels
      }
      printer.methodSignature(params, name = "")
    }
    def infoString(sym: Symbol, info: Type): String = sym match {
      case m: MethodSymbol => methodString(m, info)
      case _ => sym.infoString(info)
    }
    def detailString(r: Member): String = {
      qual match {
        case Some(tpe) if !r.sym.hasPackageFlag =>
          // Compute type parameters based on the qualifier.
          // Example: Map[Int, String].applyOrE@@
          // Before: getOrElse[V1 >: V]     (key: K,   default: => V1): V1
          // After:  getOrElse[V1 >: String](key: Int, default: => V1): V1
          infoString(r.sym, tpe.memberType(r.sym))
        case _ =>
          if (r.sym.isClass || r.sym.isModuleOrModuleClass || r.sym.hasPackageFlag) {
            " " + r.sym.owner.fullName
          } else {
            if (r.sym.hasRawInfo) infoString(r.sym, r.sym.rawInfo)
            else "<_>"
          }
      }
    }
    val sorted = i.results.sorted(new Ordering[Member] {
      override def compare(o1: Member, o2: Member): Int = {
        val byRelevance =
          Integer.compare(relevancePenalty(o1), relevancePenalty(o2))
        if (byRelevance != 0) byRelevance
        else {
          val byIdentifier =
            IdentifierComparator.compare(o1.sym.name, o2.sym.name)
          if (byIdentifier != 0) byIdentifier
          else detailString(o1).compareTo(detailString(o2))
        }
      }
    })
    val items = sorted.iterator.zipWithIndex.map {
      case (r, idx) =>
        params.checkCanceled()
        val label = r.symNameDropLocal.decoded
        val item = new CompletionItem(label)
        // TODO(olafur): investigate TypeMembers.prefix field, maybe it can replace qual match here.
        val detail = detailString(r)
        r match {
          case w: WorkspaceMember =>
            item.setInsertText(w.sym.fullName)
          case _ =>
        }
        item.setDetail(detail)
        item.setKind(completionItemKind(r))
        item.setSortText(f"${idx}%05d")
        val commitCharacter =
          if (r.sym.isMethod && !isNullary(r.sym)) "("
          else "."
        item.setCommitCharacters(List(commitCharacter).asJava)
        if (idx == 0) {
          item.setPreselect(true)
        }
        item
    }
    val result = new CompletionItems(kind, items.toSeq.asJava)
    result.setIsIncomplete(i.isIncomplete)
    result
  }

  def isNullary(sym: Symbol): Boolean = sym.info match {
    case _: NullaryMethodType => true
    case PolyType(_, _: NullaryMethodType) => true
    case _ => false
  }

  case class InterestingMembers(
      results: List[Member],
      searchResult: SymbolSearch.Result
  ) {
    def isIncomplete: Boolean = searchResult == SymbolSearch.Result.INCOMPLETE
  }

  private def filterInteresting(
      completions: List[Member],
      kind: LookupKind,
      query: String,
      pos: Position
  ): InterestingMembers = {
    val isUninterestingSymbol = Set[Symbol](
      // the methods == != ## are arguably "interesting" but they're here becuase
      // - they're short so completing them doesn't save you keystrokes
      // - they're available on everything so you
      definitions.Any_==,
      definitions.Any_!=,
      definitions.Any_##,
      definitions.Object_==,
      definitions.Object_!=,
      definitions.Object_##,
      definitions.Object_eq,
      definitions.Object_ne,
      definitions.RepeatedParamClass,
      definitions.ByNameParamClass,
      definitions.JavaRepeatedParamClass,
      definitions.Object_notify,
      definitions.Object_notifyAll,
      definitions.Object_notify,
      definitions.getMemberMethod(definitions.ObjectClass, termNames.wait_),
      definitions.getMemberMethod(
        definitions.getMemberClass(
          definitions.PredefModule,
          TypeName("ArrowAssoc")
        ),
        TermName("→").encode
      )
    ).flatMap(_.alternatives)
    val isSeen = mutable.Set.empty[String]
    val buf = List.newBuilder[Member]
    def visit(head: Member): Boolean = {
      val id =
        if (head.sym.isClass || head.sym.isModule) {
          head.sym.fullName
        } else {
          semanticdbSymbol(head.sym)
        }
      if (!isSeen(id) && !isUninterestingSymbol(head.sym)) {
        isSeen += id
        buf += head
        true
      } else {
        false
      }
    }
    completions.foreach(visit)
    val searchResults =
      if (kind == LookupKind.Scope) {
        workspaceSymbolListMembers(query, pos, visit)
      } else {
        SymbolSearch.Result.COMPLETE
      }

    InterestingMembers(buf.result(), searchResults)
  }

  private def isFunction(symbol: Symbol): Boolean = {
    compiler.definitions.isFunctionSymbol(
      symbol.info.finalResultType.typeSymbol
    )
  }

  private def completionItemKind(r: Member): CompletionItemKind = {
    import org.eclipse.lsp4j.{CompletionItemKind => k}
    val symbol = r.sym
    val symbolIsFunction = isFunction(symbol)
    if (symbol.hasPackageFlag) k.Module
    else if (symbol.isPackageObject) k.Module
    else if (symbol.isModuleOrModuleClass) k.Module
    else if (symbol.isTrait) k.Interface
    else if (symbol.isJava) k.Interface
    else if (symbol.isClass) k.Class
    else if (symbol.isMethod) k.Method
    else if (symbol.isCaseAccessor) k.Field
    else if (symbol.isVal && !symbolIsFunction) k.Value
    else if (symbol.isVar && !symbolIsFunction) k.Variable
    else if (symbol.isTypeParameterOrSkolem) k.TypeParameter
    else if (symbolIsFunction) k.Function
    else k.Value
  }

  /** Computes the relative relevance of a symbol in the completion list
   * This is an adaptation of
   * https://github.com/scala-ide/scala-ide/blob/a17ace0ee1be1875b8992664069d8ad26162eeee/org.scala-ide.sdt.core/src/org/scalaide/core/completion/ProposalRelevanceCalculator.scala
   */
  private def computeRelevancePenalty(
      sym: Symbol,
      viaImplicitConversion: Boolean,
      isInherited: Boolean
  ): Int = {
    import MemberOrdering._
    var relevance = 0
    // local symbols are more relevant
    if (!sym.isLocalToBlock) relevance |= IsNotLocalByBlock
    // fields are more relevant than non fields
    if (!sym.hasGetter) relevance |= IsNotGetter
    // non-inherited members are more relevant
    if (isInherited) relevance |= IsInherited
    // symbols whose owner is a base class are less relevant
    val isInheritedBaseMethod = sym.owner match {
      case definitions.AnyClass | definitions.AnyRefClass |
          definitions.ObjectClass =>
        true
      case _ =>
        false
    }
    if (isInheritedBaseMethod)
      relevance |= IsInheritedBaseMethod
    // symbols not provided via an implicit are more relevant
    if (viaImplicitConversion) relevance |= IsImplicitConversion
    if (sym.hasPackageFlag) relevance |= IsPackage
    // accessors of case class members are more relevant
    if (!sym.isCaseAccessor) relevance |= IsNotCaseAccessor
    // public symbols are more relevant
    if (!sym.isPublic) relevance |= IsNotCaseAccessor
    // synthetic symbols are less relevant (e.g. `copy` on case classes)
    if (sym.isSynthetic) relevance |= IsSynthetic
    relevance
  }

  private def safeCompletionsAt(
      position: Position
  ): (Option[Type], LookupKind, InterestingMembers) = {
    def expected(e: Throwable) = {
      scribe.info(e.getMessage)
      (
        None,
        LookupKind.None,
        InterestingMembers(Nil, SymbolSearch.Result.COMPLETE)
      )
    }
    try {
      val completions = completionsAt(position)
      params.checkCanceled()
      val matchingResults = completions.matchingResults { entered => name =>
        Fuzzy.matches(entered, name)
      }
      val kind = completions match {
        case _: CompletionResult.ScopeMembers =>
          LookupKind.Scope
        case _: CompletionResult.TypeMembers =>
          LookupKind.Type
        case _ =>
          LookupKind.None
      }
      val items = filterInteresting(
        matchingResults,
        kind,
        completions.name.toString,
        position
      )
      params.checkCanceled()
      val qual = completions match {
        case t: CompletionResult.TypeMembers =>
          Option(t.qualifier.tpe)
        case _ =>
          None
      }
      (qual, kind, items)
    } catch {
      case e: CyclicReference
          if e.getMessage.contains("illegal cyclic reference") =>
        expected(e)
      case e: ScalaReflectionException
          if e.getMessage.contains("not a module") =>
        expected(e)
      case e: NullPointerException =>
        expected(e)
      case e: StringIndexOutOfBoundsException =>
        expected(e)
    }
  }

  /**
   * Returns a high number for less relevant symbols and low number for relevant numbers.
   *
   * Relevance is computed based on several factors such as
   * - local vs global
   * - public vs private
   * - synthetic vs non-synthetic
   */
  def relevancePenalty(m: Member): Int = m match {
    case TypeMember(sym, _, true, isInherited, _) =>
      computeRelevancePenalty(sym, m.implicitlyAdded, isInherited)
    case w: WorkspaceMember =>
      MemberOrdering.IsWorkspaceSymbol + w.sym.name.length()
    case ScopeMember(sym, _, true, _) =>
      computeRelevancePenalty(sym, m.implicitlyAdded, isInherited = false)
    case _ =>
      Int.MaxValue
  }

  class WorkspaceMember(sym: Symbol)
      extends ScopeMember(sym, NoType, true, EmptyTree)

  private def workspaceSymbolListMembers(
      query: String,
      pos: Position,
      visit: Member => Boolean
  ): SymbolSearch.Result = {
    if (query.isEmpty) SymbolSearch.Result.COMPLETE
    else {
      val context = doLocateContext(pos)
      val visitor = new CompilerSearchVisitor(
        query,
        compiler.metalsContainsPackage,
        top => {
          var added = 0
          for {
            sym <- loadSymbolFromClassfile(top)
            if context.lookupSymbol(sym.name, _ => true).symbol != sym
          } {
            if (visit(new WorkspaceMember(sym))) {
              added += 1
            }
          }
          added
        }
      )
      search.search(query, buildTargetIdentifier, visitor)
    }
  }

  private def loadSymbolFromClassfile(
      classfile: SymbolSearchCandidate
  ): List[Symbol] = {
    try {
      val pkgName = classfile.packageString.stripSuffix("/").replace('/', '.')
      val pkg = rootMirror.staticPackage(pkgName)
      def isAccessible(sym: Symbol): Boolean = {
        sym != NoSymbol && {
          sym.info // needed to fill complete symbol
          sym.isPublic
        }
      }
      val members = classfile.names.foldLeft(List[Symbol](pkg)) {
        case (accum, name) =>
          accum.flatMap { sym =>
            if (!isAccessible(sym) || !sym.isModuleOrModuleClass) Nil
            else {
              sym.info.member(TermName(name)) ::
                sym.info.member(TypeName(name)) ::
                Nil
            }
          }
      }
      members.filter(sym => isAccessible(sym))
    } catch {
      case NonFatal(_) =>
        scribe.error(s"no such symbol: $classfile")
        Nil
    }
  }

}
