/*
 * Copyright 2017-2019 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zio.delegate

import scala.reflect.macros.blackbox.Context
import scala.reflect.macros.TypecheckException

private[delegate] class Macros(val c: Context) {
  import c.universe._

  def mixImpl[A: WeakTypeTag, B: WeakTypeTag]: c.Tree = {
    val aTT = weakTypeOf[A]
    val bTT = weakTypeOf[B]

    val bTTComps = getTypeComponents(bTT) // we need do this because refinements does not count as a trait
    // aT may extends a class bT may not as it will be mixed in
    preconditions(
      (!aTT.typeSymbol.isFinal -> s"${aTT.typeSymbol.toString()} must be nonfinal class or trait.") ::
        bTTComps.map(t => t.typeSymbol.asClass.isTrait -> s"${t.typeSymbol.toString()} needs to be a trait."): _*
    )

    val aName = TermName(c.freshName("a"))
    val bName = TermName(c.freshName("b"))
    val resultType = {
      val candidate =
        s"${(getTypeComponents(aTT) ++ bTTComps).map(t => localName(t.typeSymbol.asClass)).mkString(" with ")}"
      parseTypeString(candidate).fold(e => abort(s"Failed typechecking calculated type $candidate: $e"), identity)
    }
    val body = {
      val methods = overlappingMethods(aTT, resultType).map((_, aName)).toMap ++
        overlappingMethods(bTT, resultType).map((_, bName)).toMap

      methods.filterNot { case (m, _) => isObjectMethod(m) }.map {
        case (m, owner) => delegateMethodDef(m, owner)
      }
    }
    val resultTypeName = TypeName(c.freshName("result"))
    q"""
    ${c.parse(s"abstract class $resultTypeName extends $resultType")}
    new Mix[$aTT, $bTT] {
      def mix($aName: $aTT, $bName: $bTT): ${resultType} = {
        new ${resultTypeName} {
          ..$body
        }
      }
    }
    """
  }

  def delegateImpl(annottees: c.Expr[Any]*): c.Tree = {

    case class Arguments(verbose: Boolean, forwardObjectMethods: Boolean, generateTraits: Boolean)

    val args: Arguments = c.prefix.tree match {
      case Apply(_, args) =>
        val verbose: Boolean = args.collectFirst {
          case q"verbose = $cfg" =>
            c.eval(c.Expr[Boolean](cfg))
        }.getOrElse(false)
        val forwardObjectMethods = args.collectFirst {
          case q"forwardObjectMethods = $cfg" =>
            c.eval(c.Expr[Boolean](cfg))
        }.getOrElse(false)
        val generateTraits = args.collectFirst {
          case q"generateTraits = $cfg" =>
            c.eval(c.Expr[Boolean](cfg))
        }.getOrElse(true)
        Arguments(verbose, forwardObjectMethods, generateTraits)
      case other => abort("not possible - macro invoked on type that does not have @delegate: " + showRaw(other))
    }

    def isBlackListed(m: MethodSymbol) =
      if (!args.forwardObjectMethods) isObjectMethod(m) else false

    def modifiedClass(classDecl: ClassDef, delegateTo: ValDef): c.Tree = {
      val q"..$mods class $className(..$fields) extends ..$bases { ..$body }" = classDecl
      val existingMethods = body
        .flatMap(
          tree =>
            tree match {
              case a @ DefDef(_, n, _, _, _, _) => Some(n)
              case a @ ValDef(_, n, _, _)       => Some(n)
              case _                            => None
            }
        )
        .toSet

      val (toName, toType) = typeCheckVal(delegateTo)
        .fold(e => abort(s"Failed typechecking annotated member. Is it defined in local scope?: $e"), identity)
      val additionalTraits =
        if (args.generateTraits)
          getTraits(toType) -- bases.flatMap(b => getTraits(c.typecheck(b, c.TYPEmode).tpe)).toSet
        else Set.empty
      val resultType = {
        val candidate = (bases.map(_.toString()) ++ additionalTraits.map(localName).toList).mkString(" with ")
        parseTypeString(candidate).fold(e => abort(s"Failed typechecking calculated type $candidate: $e"), identity)
      }
      val extensions = overlappingMethods(toType, resultType, !isBlackListed(_))
        .filterNot(m => existingMethods.contains(m.name))
        .map(delegateMethodDef(_, toName))

      val resultTypeName = TypeName(c.freshName)
      q"""
      ${c.parse(s"abstract class $resultTypeName extends $resultType")}
      $mods class $className(..$fields) extends $resultTypeName { ..${body ++ extensions} }
      """
    }

    annottees.map(_.tree) match {
      case (valDecl: ValDef) :: (classDecl: ClassDef) :: Nil =>
        val modified = modifiedClass(classDecl, valDecl)
        if (args.verbose) showInfo(modified.toString())
        modified
      case _ => abort("Invalid annottee")
    }
  }

  final private[this] def delegateMethodDef(m: MethodSymbol, to: TermName) = {
    val name  = m.name
    val rType = m.returnType
    val mods =
      if (!m.isAbstract) Modifiers(Flag.OVERRIDE)
      else Modifiers()

    if (m.paramss.isEmpty) {
      q"$mods val $name: $rType = $to.$name"
    } else {
      val typeParams = m.typeParams.map(internal.typeDef(_))
      val paramLists = m.paramLists.map(_.map(internal.valDef(_)))
      q"""
      $mods def $name[..${typeParams}](...$paramLists): $rType = {
        $to.${name}(...${paramLists.map(_.map(_.name))})
      }
      """
    }
  }

  final private[this] def isObjectMethod(m: MethodSymbol): Boolean =
    Set(
      "java.lang.Object.clone",
      "java.lang.Object.hashCode",
      "java.lang.Object.finalize",
      "java.lang.Object.equals",
      "java.lang.Object.toString",
      "scala.Any.getClass"
    ).contains(m.fullName)

  final private[this] def getTraits(t: Type): Set[ClassSymbol] = {
    def loop(stack: List[ClassSymbol], traits: Vector[ClassSymbol] = Vector()): Vector[ClassSymbol] = stack match {
      case x :: xs =>
        loop(xs, if (x.isTrait) traits :+ x else traits)
      case Nil => traits
    }
    loop(t.baseClasses.map(_.asClass)).toSet
  }

  final private[this] val typeCheckVal: ValDef => Either[TypecheckException, (TermName, Type)] = {
    case ValDef(_, tname, tpt, _) =>
      val tpe = try {
        Right(c.typecheck(tpt.duplicate, c.TYPEmode).tpe)
      } catch {
        case e: TypecheckException => Left(e)
      }
      tpe.right.map((tname, _))
  }

  final private[this] def parseTypeString(str: String): Either[TypecheckException, Type] =
    try {
      Right(c.typecheck(c.parse(s"null.asInstanceOf[$str]"), c.TYPEmode).tpe)
    } catch {
      case e: TypecheckException => Left(e)
    }

  final private[this] def localName(symbol: ClassSymbol): String =
    parseTypeString(symbol.fullName).fold(
      _ => {
        val path = "_root_" +: symbol.fullName.split('.')
        path
          .zip(("_root_" +: enclosing.split('.')).take(path.length - 1).padTo(path.length, ""))
          .dropWhile { case ((l, r)) => l == r }
          .map(_._1)
          .mkString(".")
      },
      _ => symbol.fullName
    )

  final private[this] val enclosing: String = c.enclosingClass match {
    case clazz if clazz.isEmpty => c.enclosingPackage.symbol.fullName
    case clazz                  => clazz.symbol.fullName
  }

  final private[this] def overlappingMethods(
    from: Type,
    to: Type,
    filter: MethodSymbol => Boolean = _ => true
  ): Set[MethodSymbol] = {
    def isVisible(m: MethodSymbol) =
      m.isPublic || enclosing.startsWith(m.privateWithin.fullName)

    to.baseClasses
      .map(_.asClass.selfType)
      .filter(from <:< _)
      .flatMap { s =>
        s.members
          .flatMap(m => to.member(m.name).alternatives.map(_.asMethod).find(_ == m))
          .filter(m => !m.isConstructor && !m.isFinal && isVisible(m) && filter(m))
      }
      .toSet
  }

  final private[this] def showInfo(s: String) =
    c.info(c.enclosingPosition, s.split("\n").mkString("\n |---macro info---\n |", "\n |", ""), true)

  final private[this] def abort(s: String) =
    c.abort(c.enclosingPosition, s)

  final private[this] def preconditions(conds: (Boolean, String)*): Unit =
    conds.foreach {
      case (cond, s) =>
        if (!cond) abort(s)
    }

  final private[this] def getTypeComponents(t: Type): List[Type] = t.dealias match {
    case RefinedType(parents, _) => parents.flatMap(p => getTypeComponents(p))
    case t                       => List(t)
  }
}
