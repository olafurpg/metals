package scala.meta.internal.pc

import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import scala.collection.JavaConverters._
import scala.meta.pc
import scala.meta.pc.SymbolIndexer

class SignatureHelpProvider(
    val compiler: ScalaCompiler,
    indexer: SymbolIndexer
) {
  import compiler._

  def signatureHelp(
      filename: String,
      text: String,
      offset: Int
  ): SignatureHelp = {
    val unit = ScalaPC.addCompilationUnit(
      global = compiler,
      code = text,
      filename = filename,
      cursor = cursor(offset, text)
    )
    val pos = unit.position(offset)
    compiler.typeCheck(unit)
    EnclosingMethodCall
      .fromPosition(pos, unit.body.asInstanceOf[compiler.Tree])
      .map(toSignatureHelp)
      .getOrElse(new SignatureHelp())
  }

  case class Arg(
      tree: Tree,
      paramsIndex: Int,
      paramIndex: Int
  ) {
    def matches(param: Symbol, i: Int, j: Int): Boolean =
      paramsIndex == i && {
        paramIndex == j ||
        (paramIndex > j && definitions.isRepeatedParamType(param.tpe))
      }
  }

  // A method call like `function[A, B](a, b)(c, d)`
  case class MethodCall(
      qual: Tree,
      symbol: Symbol,
      tparams: List[Tree],
      argss: List[List[Tree]]
  ) {
    def alternatives: List[Symbol] = symbol match {
      case o: ModuleSymbol =>
        o.info.member(compiler.nme.apply).alternatives
      case o: ClassSymbol =>
        o.info.member(compiler.termNames.CONSTRUCTOR).alternatives
      case m: MethodSymbol =>
        m.owner.asClass.info.member(symbol.name).alternatives
      case _ =>
        symbol.alternatives
    }
    def nonOverload: Symbol =
      alternatives.headOption.getOrElse(symbol)
    def gparamss: List[List[Symbol]] = {
      if (symbol.typeParams.isEmpty) nonOverload.paramLists
      else nonOverload.typeParams :: nonOverload.paramLists
    }
    def all: List[List[Tree]] =
      if (tparams.isEmpty) argss
      else tparams :: argss
    def paramTree(i: Int, j: Int): List[Tree] =
      all.lift(i).flatMap(_.lift(j)).toList
    def margss: List[List[Tree]] = {
      all
    }
  }
  object MethodCall {
    def unapply(tree: Tree): Option[MethodCall] = tree match {
      case TypeApply(qual, targs) =>
        Some(MethodCall(qual, treeSymbol(tree), targs, Nil))
      case Apply(qual, args) =>
        var tparams: List[Tree] = Nil
        def loop(
            t: Tree,
            paramss: List[List[Symbol]],
            accum: List[List[Tree]]
        ): List[List[Tree]] = {
          (t, paramss) match {
            case (Apply(qual0, args0), _ :: tail) =>
              loop(qual0, tail, args0 :: accum)
            case (TypeApply(_, args0), _) =>
              tparams = args0
              accum
            case _ =>
              accum
          }
        }
        val symbol = treeSymbol(tree)
        val argss = symbol.paramss match {
          case _ :: tail =>
            loop(qual, tail, args :: Nil)
          case _ =>
            args :: Nil
        }
        Some(MethodCall(qual, symbol, tparams, argss))
      case _ => None
    }
  }

  // Returns a cursor offset only if the cursor is between parentheses or brackets:
  // Example where we add a cursor      : foo(@@)
  // Example where we don't add a cursor: foo(arg@@)
  def cursor(offset: Int, text: String): Option[Int] =
    if (offset > 0 && offset < text.length) {
      text.charAt(offset - 1) match {
        case '(' | '[' =>
          text.charAt(offset) match {
            case ')' | ']' =>
              Some(offset)
            case _ =>
              None
          }
        case _ =>
          None
      }
    } else {
      None
    }

  // Extractor for both term and type applications like `foo(1)` and foo[T]`
  object TreeApply {
    def unapply(tree: Tree): Option[(Tree, List[Tree])] = tree match {
      case TypeApply(qual, args) => Some(qual -> args)
      case Apply(qual, args) => Some(qual -> args)
      case _ => None
    }
  }

  case class EnclosingMethodCall(
      call: MethodCall,
      activeArg: Arg
  ) {
    def alternatives: List[Symbol] = call.alternatives
    def symbol: Symbol = call.symbol
  }

  object EnclosingMethodCall {
    def fromPosition(pos: Position, body: Tree): Option[EnclosingMethodCall] =
      new MethodCallTraverser(pos).fromTree(body)
  }

  // A traverser that finds the nearest enclosing method call for a given position.
  class MethodCallTraverser(pos: Position) extends Traverser {
    private var activeCallsite: MethodCall = _
    private var activeArg: Arg = _
    def fromTree(body: Tree): Option[EnclosingMethodCall] = {
      traverse(body)
      if (activeCallsite == null) {
        None
      } else {
        if (activeCallsite.alternatives.isEmpty) {
          None
        } else {
          Some(
            EnclosingMethodCall(
              activeCallsite,
              activeArg
            )
          )
        }
      }
    }
    override def traverse(tree: compiler.Tree): Unit = {
      if (isEligible(tree) && tree.pos.includes(pos)) {
        visit(tree)
      }
    }
    def isEligible(tree: Tree): Boolean = {
      !tree.pos.isTransparent && tree.tpe != null
    }
    def visit(tree: Tree): Unit = tree match {
      case MethodCall(call) =>
        for {
          (args, i) <- call.margss.zipWithIndex
          (tree, j) <- args.zipWithIndex
        } {
          if (tree.pos.includes(pos)) {
            activeCallsite = call
            activeArg = Arg(tree, i, j)
          }
          traverse(tree)
        }
        super.traverse(call.qual)
      case _ =>
        super.traverse(tree)
    }
  }

  // Same as `tree.symbol` but tries to recover from type errors
  // by using the completions API.
  def treeSymbol(tree0: Tree): Symbol = {
    if (tree0.symbol != NoSymbol && !tree0.symbol.isError) {
      tree0.symbol
    } else {
      def applyQualifier(tree: Tree): Option[RefTree] = tree match {
        case t: RefTree => Some(t)
        case TreeApply(qual, _) => applyQualifier(qual)
        case _ =>
          None
      }
      val completionFallback = for {
        qual <- applyQualifier(tree0)
        completion <- completionsAt(qual.pos.focus).results
          .find(_.sym.name == qual.name)
        if !completion.sym.isErroneous
      } yield completion.sym
      completionFallback.getOrElse {
        val qual = tree0 match {
          case TreeApply(q @ Select(New(_), _), _) => q
          case _ => tree0
        }
        compiler.typedTreeAt(qual.pos).symbol
      }
    }
  }

  case class ParamIndex(j: Int, param: Symbol)

  def toSignatureHelp(t: EnclosingMethodCall): SignatureHelp = {
    val activeParent =
      if (t.symbol.isOverloaded) t.symbol.alternatives.head
      else t.symbol
    var activeSignature: Integer = null
    var activeParameter: Integer = null
    val infos = t.alternatives.zipWithIndex.collect {
      case (method: MethodSymbol, i) =>
        val isActiveSignature = method == activeParent
        if (isActiveSignature) {
          activeSignature = i
          val gparamss = for {
            (params, i) <- this.mparamss(method).zipWithIndex
            (param, j) <- params.zipWithIndex
          } yield (param, i, j)
          val activeIndex = gparamss.zipWithIndex.collectFirst {
            case ((param, i, j), flat) if t.activeArg.matches(param, i, j) =>
              flat
          }
          activeIndex match {
            case Some(value) =>
              val paramCount = math.max(0, gparamss.length - 1)
              activeParameter = math.min(value, paramCount)
            case _ =>
          }
        }
        toSignatureInformation(t, method, isActiveSignature)
    }
    new SignatureHelp(infos.asJava, activeSignature, activeParameter)
  }

  def mparamss(method: MethodSymbol): List[List[compiler.Symbol]] =
    if (method.typeParams.isEmpty) method.paramLists
    else method.typeParams :: method.paramLists

  def toSignatureInformation(
      t: EnclosingMethodCall,
      method: MethodSymbol,
      isActiveSignature: Boolean
  ): SignatureInformation = {
    val mparamss = this.mparamss(method)
    def arg(i: Int, j: Int): Option[Tree] =
      t.call.all.lift(i).flatMap(_.lift(j))
    val info = methodInfo(method)
    val infoParamsA: Seq[pc.ParameterInformation] = info match {
      case Some(value) =>
        value.typeParameters().asScala ++
          value.parameters().asScala
      case None =>
        IndexedSeq.empty
    }
    val infoParams = infoParamsA.lift
    var k = 0
    val paramLabels = mparamss.zipWithIndex.map {
      case (params, i) =>
        params.zipWithIndex.map {
          case (param, j) =>
            val index = k
            k += 1
            val paramInfo = infoParams(index)
            val name = paramInfo match {
              case Some(value) => value.name()
              case None => param.nameString
            }
            val label =
              if (param.isTypeParameter) {
                name
              } else {
                val default =
                  if (param.isParamWithDefault) " = {}"
                  else ""
                if (isActiveSignature) {
                  val tpe = param.info.toLongString
                  s"$name: $tpe$default"
                } else {
                  s"$name: ${param.info.toLongString}$default"
                }
              }
            val docstring =
              paramInfo.map(_.docstring().orElse("")).getOrElse("")
            val lparam = new ParameterInformation(label, docstring)
            // TODO(olafur): use LSP 3.14.0 ParameterInformation.label offsets instead of strings
            // once this issue is fixed https://github.com/eclipse/lsp4j/issues/300
            if (isActiveSignature && t.activeArg.matches(param, i, j)) {
              arg(i, j) match {
                case Some(a) if !a.tpe.isErroneous =>
                  val tpe = a.tpe.widen.toLongString
                  val typeString =
                    if (tpe.endsWith("=> Null")) {
                      tpe.stripSuffix("=> Null") + "=> ???"
                    } else {
                      tpe
                    }
                  if (!lparam.getLabel.endsWith(typeString)) {
                    val content = new MarkupContent()
                    content.setKind("markdown")
                    content.setValue(
                      "```scala\n" + typeString + "\n```\n" + docstring
                    )
                    lparam.setDocumentation(content)
                  }
                case _ =>
              }
            }
            lparam
        }
    }
    val methodSignature = paramLabels.iterator.zipWithIndex
      .map {
        case (params, i) =>
          if (method.typeParams.nonEmpty && i == 0) {
            params.map(_.getLabel).mkString("[", ", ", "]")
          } else {
            params.map(_.getLabel).mkString("(", ", ", ")")
          }
      }
      .mkString(
        method.nameString,
        "",
        s": ${method.returnType.toLongString}"
      )
    new SignatureInformation(
      methodSignature,
      info.fold("")(_.docstring()),
      paramLabels.iterator.flatten.toSeq.asJava
    )
  }

}
