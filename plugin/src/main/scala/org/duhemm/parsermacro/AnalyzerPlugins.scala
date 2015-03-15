package org.duhemm.parsermacro

import scala.reflect.macros.util.Traces
import scala.reflect.internal.util.ScalaClassLoader
import scala.reflect.runtime.ReflectionUtils
import scala.reflect.internal.Mode

trait AnalyzerPlugins extends Traces { self: Plugin =>
  import global._
  import analyzer._
  import definitions._
  import treeInfo._

  def globalSettings = global.settings

  object MacroPlugin extends analyzer.MacroPlugin {
    /**
     * Typechecks the right-hand side of a macro definition (which typically features
     * a mere reference to a macro implementation).
     *
     * Default implementation provided in `self.standardTypedMacroBody` makes sure that the rhs
     * resolves to a reference to a method in either a static object or a macro bundle,
     * verifies that the referred method is compatible with the macro def and upon success
     * attaches a macro impl binding to the macro def's symbol.
     *
     * $nonCumulativeReturnValueDoc.
     */
    override def pluginsTypedMacroBody(typer: Typer, ddef: DefDef): Option[Tree] = {
      try {
        verifyMacroShape(typer, ddef)
        Some(EmptyTree)
      } catch {
        case InvalidMacroShapeException(pos, msg) =>
          macroLogVerbose(s"parser macro $ddef.name has an invalid shape:\n$msg")
          None
      }
    }

    /**
     * Figures out whether the given macro definition is blackbox or whitebox.
     *
     * Default implementation provided in `self.standardIsBlackbox` loads the macro impl binding
     * and fetches boxity from the "isBlackbox" field of the macro signature.
     *
     * $nonCumulativeReturnValueDoc.
     */
    override def pluginsIsBlackbox(macroDef: Symbol): Option[Boolean] = None

    /**
     * Expands an application of a def macro (i.e. of a symbol that has the MACRO flag set),
     * possibly using the current typer mode and the provided prototype.
     *
     * Default implementation provided in `self.standardMacroExpand` figures out whether the `expandee`
     * needs to be expanded right away or its expansion has to be delayed until all undetermined
     * parameters are inferred, then loads the macro implementation using `self.pluginsMacroRuntime`,
     * prepares the invocation arguments for the macro implementation using `self.pluginsMacroArgs`,
     * and finally calls into the macro implementation. After the call returns, it typechecks
     * the expansion and performs some bookkeeping.
     *
     * This method is typically implemented if your plugin requires significant changes to the macro engine.
     * If you only need to customize the macro context, consider implementing `pluginsMacroArgs`.
     * If you only need to customize how macro implementation are invoked, consider going for `pluginsMacroRuntime`.
     *
     * $nonCumulativeReturnValueDoc.
     */
    override def pluginsMacroExpand(typer: Typer, expandee: Tree, mode: Mode, pt: Type): Option[Tree] = {

      expandee match {
        case ParserMacroApplication(app, rawArguments, _) =>
          val arguments = macroArgs(typer, app)
          val runtime = macroRuntime(app)
          val originalExpandee = app + rawArguments.mkString("#(", ")#(", ")")

          runtime(arguments) match {
            case expanded: scala.meta.Tree =>
              import scala.meta.dialects.Scala211
              import scala.meta.ui._

              Some(typer.typed(arguments.c.parse(expanded.toString), pt))

            case _ =>
              None
          }

        case _ => None
      }
    }

    /**
     * Computes the arguments that need to be passed to the macro impl corresponding to a particular expandee.
     *
     * Default implementation provided in `self.standardMacroArgs` instantiates a `scala.reflect.macros.contexts.Context`,
     * gathers type and value arguments of the macro application and throws them together into `MacroArgs`.
     *
     * $nonCumulativeReturnValueDoc.
     */
    override def pluginsMacroArgs(typer: Typer, expandee: Tree): Option[MacroArgs] = {
      import scala.meta.dialects.Scala211
      import scala.meta.Input.String.{ apply => string2input }

      expandee match {
        case ParserMacroApplication(treeInfo.Applied(core, _, _), rawArguments, _) =>
          val prefix = core match { case Select(qual, _) => qual ; case _ => EmptyTree }
          val context = macroContext(typer, prefix, expandee)
          val tokenized = rawArguments map string2input map (_.tokens)
          Some(MacroArgs(context, tokenized))

        case _ => None
      }
    }

    /**
     * Summons a function that encapsulates macro implementation invocations for a particular expandee.
     *
     * Default implementation provided in `self.standardMacroRuntime` returns a function that
     * loads the macro implementation binding from the macro definition symbol,
     * then uses either Java or Scala reflection to acquire the method that corresponds to the impl,
     * and then reflectively calls into that method.
     *
     * $nonCumulativeReturnValueDoc.
     */
    override def pluginsMacroRuntime(expandee: Tree): Option[MacroRuntime] = {
      expandee match {
        case ParserMacroApplication(_, _, MacroImplBinding(false, true, className, methName, signature, targs)) =>

          val classpath = global.classPath.asURLs
          val classloader = ScalaClassLoader.fromURLs(classpath, self.getClass.getClassLoader)
          val implClass = Class.forName(className, true, classloader)

          implClass.getMethods find (_.getName == methName) map { implMethod =>
            val implInstance = ReflectionUtils.staticSingletonInstance(classloader, className)
            (x: MacroArgs) => implMethod.invoke(implInstance, x.others)
          }

        case _ =>
          None
      }
    }

    /**
     * Creates a symbol for the given tree in lexical context encapsulated by the given namer.
     *
     * Default implementation provided in `namer.standardEnterSym` handles MemberDef's and Imports,
     * doing nothing for other trees (DocDef's are seen through and rewrapped). Typical implementation
     * of `enterSym` for a particular tree flavor creates a corresponding symbol, assigns it to the tree,
     * enters the symbol into scope and then might even perform some code generation.
     *
     * $nonCumulativeReturnValueDoc.
     */
    override def pluginsEnterSym(namer: Namer, tree: Tree): Boolean = false

    /**
     * Makes sure that for the given class definition, there exists a companion object definition.
     *
     * Default implementation provided in `namer.standardEnsureCompanionObject` looks up a companion symbol for the class definition
     * and then checks whether the resulting symbol exists or not. If it exists, then nothing else is done.
     * If not, a synthetic object definition is created using the provided factory, which is then entered into namer's scope.
     *
     * $nonCumulativeReturnValueDoc.
     */
    override def pluginsEnsureCompanionObject(namer: Namer, cdef: ClassDef, creator: ClassDef => Tree = companionModuleDef(_)): Option[Symbol] = None

    /**
     * Prepares a list of statements for being typechecked by performing domain-specific type-agnostic code synthesis.
     *
     * Trees passed into this method are going to be named, but not typed.
     * In particular, you can rely on the compiler having called `enterSym` on every stat prior to passing calling this method.
     *
     * Default implementation does nothing. Current approaches to code syntheses (generation of underlying fields
     * for getters/setters, creation of companion objects for case classes, etc) are too disparate and ad-hoc
     * to be treated uniformly, so I'm leaving this for future work.
     */
    override def pluginsEnterStats(typer: Typer, stats: List[Tree]): List[Tree] = stats

    private object ParserMacroApplication {
      def unapply(tree: Tree) = {

        val binding = tree.symbol.getAnnotation(MacroImplAnnotation) collect {
          case AnnotationInfo(_, List(pickle), _) => MacroImplBinding.unpickle(pickle)
        }

        (tree.attachments.get[ParserMacroArgumentsAttachment].toList, binding) match {
          case (ParserMacroArgumentsAttachment(arguments) :: Nil, Some(binding)) =>
            Some((tree, arguments, binding))

          case _ => None
        }
      }
    }

    private def verifyMacroShape(typer: Typer, ddef: DefDef): Unit = {

      val DefDef(mods, name, tparams, vparamss, tpt, rhs) = ddef

      // The rhs of `ddef` should be a subtype of `expectedType`
      val expectedType = typeOf[_root_.scala.collection.Seq[_root_.scala.collection.Seq[_root_.scala.meta.syntactic.Token]] => _root_.scala.meta.Tree]

      typer.silent(_.typed(markMacroImplRef(rhs.duplicate), expectedType)) match {
        case SilentResultValue(select: Select) =>
          bindMacroImpl(ddef.symbol, select)

        case SilentResultValue(res) =>
          throw InvalidMacroShapeException(ddef.pos, "...")

        case SilentTypeError(err) =>
          throw InvalidMacroShapeException(err.errPos, err.errMsg)
      }

      // TODO: Many more checks

    }

    private case class InvalidMacroShapeException(pos: Position, msg: String) extends Exception(msg + " / " + pos.getClass.getName)

  }
}