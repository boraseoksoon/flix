/*
 * Copyright 2015-2016 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.language.ast._
import ca.uwaterloo.flix.language.errors.TypeError
import ca.uwaterloo.flix.util.{InternalCompilerException, Validation}
import ca.uwaterloo.flix.util.Validation._

import scala.collection.mutable

object Typer2 {

  /**
    * A substitution is a map from type variables to types.
    */
  object Substitution {
    /**
      * Returns the empty substitution.
      */
    val empty: Substitution = Substitution(Map.empty)

    /**
      * Returns the singleton substitution mapping `x` to `tpe`.
      */
    def singleton(x: Type.Var, tpe: Type): Substitution = Substitution(Map(x -> tpe))
  }

  case class Substitution(m: Map[Type.Var, Type]) {

    /**
      * Applies `this` substitution to the given type `tpe`.
      */
    def apply(tpe: Type): Type = tpe match {
      case x: Type.Var =>
        m.get(x) match {
          case None => x
          case Some(y) if x.kind == tpe.kind => y
          case Some(y) if x.kind != tpe.kind => throw InternalCompilerException(s"Expected kind `${x.kind}' but got `${tpe.kind}'.")
        }
      case Type.Unit => Type.Unit
      case Type.Bool => Type.Bool
      case Type.Char => Type.Char
      case Type.Float32 => Type.Float32
      case Type.Float64 => Type.Float64
      case Type.Int8 => Type.Int8
      case Type.Int16 => Type.Int16
      case Type.Int32 => Type.Int32
      case Type.Int64 => Type.Int64
      case Type.BigInt => Type.BigInt
      case Type.Str => Type.Str
      case Type.Native => Type.Native
      case Type.Arrow => Type.Arrow
      case Type.FTuple(l) => Type.FTuple(l)
      case Type.FOpt => Type.FOpt
      case Type.FList => Type.FList
      case Type.FVec => Type.FVec
      case Type.FSet => Type.FSet
      case Type.FMap => Type.FMap
      case Type.Enum(name, cases) => Type.Enum(name, cases.map(x => (x._1, apply(x._2).asInstanceOf[Type.Tag])))
      case Type.Apply(t1, t2) => Type.Apply(apply(t1), apply(t2))

      // TODO: Remove once tags are gone:
      case Type.Tag(name, tag, t) => Type.Tag(name, tag, apply(t))

      case _ => throw InternalCompilerException(s"Unexpected type: `$tpe'")
    }

    /**
      * Applies `this` substitution to the given types `ts`.
      */
    def apply(ts: List[Type]): List[Type] = ts.map(t => apply(t))

    /**
      * Returns the left-biased  composition of `this` substitution with `that` substitution.
      */
    def ++(that: Substitution): Substitution = {
      Substitution(this.m ++ that.m.filter(kv => !this.m.contains(kv._1)))
    }

    /**
      * Returns the composition of `this` substitution with `that` substitution.
      */
    def @@(that: Substitution): Substitution = {
      val m = that.m.map {
        case (x, t) => x -> this.apply(t)
      }
      Substitution(m) ++ this
    }

    /**
      * Returns the composition of `this` substitution with `that` substitution,
      * provided that the two substitutions are equal on shared variables.
      */
    def merge(that: Substitution): Validation[Substitution, TypeError] = {
      val shared = this.m.keySet intersect that.m.keySet
      val conflicts = shared.filter(v => this.apply(v) != that.apply(v))
      if (conflicts.nonEmpty)
        TypeError.MergeError().toFailure
      else
        (this ++ that).toSuccess
    }

  }

  /**
    * Returns the composition of the given lists of substitutions `substs`.
    */
  def mergeAll(substs: List[Substitution]): Validation[Substitution, TypeError] = Validation.fold[Substitution, Substitution, TypeError](substs, Substitution.empty) {
    case (subst1, subst2) => subst1.merge(subst2)
  }


  /**
    * TODO: DOC
    */
  case class InferMonad[A](a: A, s: Substitution) {

    /**
      * TODO: DOC
      */
    def map(f: A => A): InferMonad[A] = InferMonad(f(a), s)

    /**
      * TODO: DOC
      */
    def flatMap(f: A => InferMonad[A]): InferMonad[A] = {
      val t = f(a)
      InferMonad(t.a, t.s @@ s)
    }

  }

  /**
    * TODO: DOC
    */
  def liftM[A](a: A): InferMonad[A] = InferMonad(a, Substitution.empty)

  /**
    * TODO: DOC
    */
  def unifyM(tpe1: Type, tpe2: Type): InferMonad[Type] = unify(tpe1, tpe2) match {
    case Validation.Success(subst, _) => InferMonad[Type](subst(tpe1), subst)
    case _ => ???
  }

  /**
    * TODO: DOC
    */
  def unifyM(tpe1: Type, tpe2: Type, tpe3: Type): InferMonad[Type] =
  for (
    tpe <- unifyM(tpe1, tpe2);
    res <- unifyM(tpe, tpe3)
  ) yield res


  /**
    * Type checks the given program.
    */
  def typer(program: NamedAst.Program)(implicit genSym: GenSym): Unit = {
    for ((ns, defns) <- program.definitions) {
      for ((name, defn) <- defns) {
        Expressions.infer(defn.exp)
      }
    }
  }

  object Expressions {

    /**
      * Infers the type of the given expression `exp0`.
      */
    def infer(exp0: NamedAst.Expression)(implicit genSym: GenSym): Unit = {

      /**
        * Infers the type of the given expression `exp0` inside the inference monad.
        */
      def visitExp(e0: NamedAst.Expression): InferMonad[Type] = e0 match {

        /*
         * Wildcard expression.
         */
        case NamedAst.Expression.Wild(tpe, loc) => liftM(tpe)

        /*
         * Variable expression.
         */
        case NamedAst.Expression.Var(sym, tvar, loc) => liftM(tvar)

        /*
         * Reference expression.
         */
        case NamedAst.Expression.Ref(ref, tvar, loc) => liftM(tvar)

        /*
         * Literal expression.
         */
        case NamedAst.Expression.Unit(loc) => liftM(Type.Unit)
        case NamedAst.Expression.True(loc) => liftM(Type.Bool)
        case NamedAst.Expression.False(loc) => liftM(Type.Bool)
        case NamedAst.Expression.Char(lit, loc) => liftM(Type.Char)
        case NamedAst.Expression.Float32(lit, loc) => liftM(Type.Float32)
        case NamedAst.Expression.Float64(lit, loc) => liftM(Type.Float64)
        case NamedAst.Expression.Int8(lit, loc) => liftM(Type.Int8)
        case NamedAst.Expression.Int16(lit, loc) => liftM(Type.Int16)
        case NamedAst.Expression.Int32(lit, loc) => liftM(Type.Int32)
        case NamedAst.Expression.Int64(lit, loc) => liftM(Type.Int64)
        case NamedAst.Expression.BigInt(lit, loc) => liftM(Type.BigInt)
        case NamedAst.Expression.Str(lit, loc) => liftM(Type.Str)

        /*
         * Lambda expression.
         */
        case NamedAst.Expression.Lambda(args, body, tpe, loc) => ???

        /*
         * Apply expression.
         */
        case NamedAst.Expression.Apply(id, exp1, args, _) => ???

        /*
         * Unary expression.
         */
        case NamedAst.Expression.Unary(op, exp1, tvar, loc) => op match {
          case UnaryOperator.LogicalNot => ???

          case UnaryOperator.Plus => ???

          case UnaryOperator.Minus => ???

          case UnaryOperator.BitwiseNegate => ???
        }

        /*
         * Binary expression.
         */
        case NamedAst.Expression.Binary(op, e1, e2, tvar, loc) => op match {
          case BinaryOperator.Plus =>
            //            val (ctx1, tpe1) = visitExp(e1, tenv0)
            //            val (ctx2, tpe2) = visitExp(e2, tenv0)
            //            constraints += TypeConstraint.Eq((ctx1, tpe1), (ctx2, tpe2))
            ???

          case BinaryOperator.Minus =>
            //            val (ctx1, tpe1) = visitExp(e1, tenv0)
            //            val (ctx2, tpe2) = visitExp(e2, tenv0)
            //            constraints += TypeConstraint.Eq((ctx1, tpe1), (ctx2, tpe2))
            ???

          case BinaryOperator.Times =>
            //            val (ctx1, tpe1) = visitExp(e1, tenv0)
            //            val (ctx2, tpe2) = visitExp(e2, tenv0)
            //            constraints += TypeConstraint.Eq((ctx1, tpe1), (ctx2, tpe2))
            ???

          case BinaryOperator.Divide =>
            //            val (ctx1, tpe1) = visitExp(e1, tenv0)
            //            val (ctx2, tpe2) = visitExp(e2, tenv0)
            //            constraints += TypeConstraint.Eq((ctx1, tpe1), (ctx2, tpe2))
            ???

          case BinaryOperator.Modulo =>
            //            val (ctx1, tpe1) = visitExp(e1, tenv0)
            //            val (ctx2, tpe2) = visitExp(e2, tenv0)
            //            constraints += TypeConstraint.Eq((ctx1, tpe1), (ctx2, tpe2))
            ???

          case BinaryOperator.Exponentiate =>
            //            val (ctx1, tpe1) = visitExp(e1, tenv0)
            //            val (ctx2, tpe2) = visitExp(e2, tenv0)
            //            constraints += TypeConstraint.Eq((ctx1, tpe1), (ctx2, tpe2))
            ???

          case BinaryOperator.Equal | BinaryOperator.NotEqual =>
            //            val (ctx1, tpe1) = visitExp(e1, tenv0)
            //            val (ctx2, tpe2) = visitExp(e2, tenv0)
            //            constraints += TypeConstraint.Eq((ctx1, tpe1), (ctx2, tpe2))
            ???

          case BinaryOperator.Less | BinaryOperator.LessEqual | BinaryOperator.Greater | BinaryOperator.GreaterEqual =>
            //            val (ctx1, tpe1) = visitExp(e1, tenv0)
            //            val (ctx2, tpe2) = visitExp(e2, tenv0)
            //            constraints += TypeConstraint.Eq((ctx1, tpe1), (ctx2, tpe2))
            ???

          case BinaryOperator.LogicalAnd | BinaryOperator.LogicalOr | BinaryOperator.Implication | BinaryOperator.Biconditional =>
            //            val (ctx1, tpe1) = visitExp(e1, tenv0)
            //            val (ctx2, tpe2) = visitExp(e2, tenv0)
            //            constraints += TypeConstraint.Eq((ctx1, tpe1), (EmptyContext, Type.Bool))
            //            constraints += TypeConstraint.Eq((ctx2, tpe2), (EmptyContext, Type.Bool))
            ???

          case BinaryOperator.BitwiseAnd | BinaryOperator.BitwiseOr | BinaryOperator.BitwiseXor | BinaryOperator.BitwiseLeftShift | BinaryOperator.BitwiseRightShift =>
            //            val (ctx1, tpe1) = visitExp(e1, tenv0)
            //            val (ctx2, tpe2) = visitExp(e2, tenv0)
            //            constraints += TypeConstraint.Eq((ctx1, tpe1), (ctx2, tpe2))
            ???

        }

        /*
         * Let expression.
         */
        case NamedAst.Expression.Let(id, sym, exp1, exp2, loc) =>
          //          val (ctx1, tpe1) = visitExp(exp1, tenv0)
          //          val (ctx2, tpe2) = visitExp(exp2, tenv0 + (sym -> tpe1))
          //          ret(id, ctx1 ::: ctx2, tpe2)
          ???


        /*
         * If-then-else expression.
         */
        case NamedAst.Expression.IfThenElse(exp1, exp2, exp3, tvar, loc) =>
          for (
            tpe1 <- visitExp(exp1);
            tpe2 <- visitExp(exp2);
            tpe3 <- visitExp(exp3);
            ____ <- unifyM(Type.Bool, tpe1);
            rtpe <- unifyM(tvar, tpe2, tpe3)
          )
            yield rtpe


        /*
         * Match expression.
         */
        case NamedAst.Expression.Match(exp1, rules, tpe, loc) =>
          liftM(Type.Int64)

        /*
           * Switch expression.
           */
        case NamedAst.Expression.Switch(id, rules, loc) =>
          ???
        //            val bodyTypes = mutable.ListBuffer.empty[Type]
        //            for ((cond, body) <- rules) {
        //              val condType = visitExp(cond, tenv0)
        //              bodyTypes += visitExp(body, tenv0)
        //              constraints += TypeConstraint.Eq(condType, Type.Bool)
        //            }
        //
        //            constraints += TypeConstraint.AllEq(bodyTypes.toList)
        //
        //            bodyTypes.head // TODO: Or generate fresh symbol?

        /*
         * Tag expression.
         */
        case NamedAst.Expression.Tag(enum, tag, exp, tvar, loc) =>
          for (
            tpe <- visitExp(exp)
          )
            yield Type.Int64 // TODO


        /*
         * Tuple expression.
         */
        case NamedAst.Expression.Tuple(id, elms, loc) =>
          //          val elements = elms.map(e => visitExp(e, tenv0))
          //          val contexts = elements.flatMap(_._1)
          //          val types = elements.map(_._2)
          //          ret(id, contexts, Type.Tuple(types))
          ???

        /*
         * None expression.
         */
        case NamedAst.Expression.FNone(tvar, loc) =>
          liftM(Type.mkFOpt(tvar))

        /*
         * Some expression.
         */
        case NamedAst.Expression.FSome(exp, tvar, loc) =>
          for (
            innerType <- visitExp(exp);
            resultType <- unifyM(tvar, Type.mkFOpt(innerType))
          ) yield resultType

        /*
         * Nil expression.
         */
        case NamedAst.Expression.FNil(tvar, loc) =>
          liftM(Type.mkFList(tvar))

        /*
         * List expression.
         */
        case NamedAst.Expression.FList(head, tail, tvar, loc) =>
          for (
            headType <- visitExp(head);
            tailType <- visitExp(tail);
            resultType <- unifyM(tvar, Type.mkFList(headType), tailType)
          ) yield resultType

        /*
         * Vector expression.
         */
        case NamedAst.Expression.FVec(elms, tvar, loc) =>
          //          val elements = elms.map(e => visitExp(e, tenv0))
          //          for ((ctx, tpe) <- elements.tail) {
          //            // assert that each element has the same type as the first element.
          //            val (ctx0, tpe0) = elements.head
          //            constraints += TypeConstraint.Eq((ctx0, tpe0), (ctx, tpe))
          //          }
          //          val contexts = elements.flatMap(_._1)
          //          ret(id, contexts, elements.head._2)
          ???

        /*
         * Set expression.
         */
        case NamedAst.Expression.FSet(id, elms, loc) =>
          ??? // TODO

        /*
         * Map expression.
         */
        case NamedAst.Expression.FMap(id, elms, loc) =>
          ??? // TODO

        /*
         * GetIndex expression.
         */
        case NamedAst.Expression.GetIndex(id, exp1, exp2, loc) =>
          //          val (ctx1, tpe1) = visitExp(exp1, tenv0)
          //          val (ctx2, tpe2) = visitExp(exp2, tenv0)
          //          constraints += TypeConstraint.Eq((ctx2, tpe2), (EmptyContext, Type.Int32))
          //          ret(id, (TypeClass.Indexed -> tpe1) :: ctx1 ::: ctx2, tpe1)
          ???

        /*
         * PutIndex expression.
         */
        case NamedAst.Expression.PutIndex(id, exp1, exp2, exp3, loc) =>
          //          val (ctx1, tpe1) = visitExp(exp1, tenv0)
          //          val (ctx2, tpe2) = visitExp(exp2, tenv0)
          //          val (ctx3, tpe3) = visitExp(exp3, tenv0)
          //          constraints += TypeConstraint.Eq((ctx2, tpe2), (EmptyContext, Type.Int32))
          //          ret(id, (TypeClass.Indexed -> tpe1) :: (TypeClass.Indexed -> tpe3) :: ctx1 ::: ctx2 ::: ctx3, tpe1)
          ???

        /*
         * Existential expression.
         */
        case NamedAst.Expression.Existential(params, e, loc) =>
          //          val tenv = params.foldLeft(tenv0) {
          //            case (macc, NamedAst.FormalParam(sym, t)) => macc + (sym -> t)
          //          }
          //          val (ctx, tpe) = visitExp(e, tenv)
          //          constraints += TypeConstraint.Eq((ctx, tpe), (EmptyContext, Type.Bool))
          //          ret(id, ctx, Type.Bool)
          ???

        /*
         * Universal expression.
         */
        case NamedAst.Expression.Universal(params, e, loc) =>
          //          val tenv = params.foldLeft(tenv0) {
          //            case (macc, NamedAst.FormalParam(sym, t)) => macc + (sym -> t)
          //          }
          //          val (ctx, tpe) = visitExp(e, tenv)
          //          constraints += TypeConstraint.Eq((ctx, tpe), (EmptyContext, Type.Bool))
          //          ret(id, ctx, Type.Bool)
          ???

        /*
         * Ascribe expression.
         */
        case NamedAst.Expression.Ascribe(exp, expectedType, loc) =>
          for (
            actualType <- visitExp(exp);
            resultType <- unifyM(actualType, expectedType)
          )
            yield resultType

        /*
         * User Error expression.
         */
        case NamedAst.Expression.UserError(tvar, loc) => liftM(tvar)

      }

      /**
        * Infers the type of the given expression `e0` under the given type environment `tenv0`.
        */
      // TODO: What is the type env exactly? Should probably be removed/refactored.
      def visitPat(p0: NamedAst.Pattern, tenv: Map[Symbol.VarSym, Type]): Validation[(Substitution, Type), TypeError] = p0 match {
        case NamedAst.Pattern.Wild(tvar, loc) => (Substitution.empty, tvar).toSuccess
        case NamedAst.Pattern.Var(sym, tvar, loc) => (Substitution.empty, tvar).toSuccess
        case NamedAst.Pattern.Unit(loc) => (Substitution.empty, Type.Unit).toSuccess
        case NamedAst.Pattern.True(loc) => (Substitution.empty, Type.Bool).toSuccess
        case NamedAst.Pattern.False(loc) => (Substitution.empty, Type.Bool).toSuccess
        case NamedAst.Pattern.Char(c, loc) => (Substitution.empty, Type.Char).toSuccess
        case NamedAst.Pattern.Float32(i, loc) => (Substitution.empty, Type.Float32).toSuccess
        case NamedAst.Pattern.Float64(i, loc) => (Substitution.empty, Type.Float64).toSuccess
        case NamedAst.Pattern.Int8(i, loc) => (Substitution.empty, Type.Int8).toSuccess
        case NamedAst.Pattern.Int16(i, loc) => (Substitution.empty, Type.Int16).toSuccess
        case NamedAst.Pattern.Int32(i, loc) => (Substitution.empty, Type.Int32).toSuccess
        case NamedAst.Pattern.Int64(i, loc) => (Substitution.empty, Type.Int64).toSuccess
        case NamedAst.Pattern.BigInt(i, loc) => (Substitution.empty, Type.BigInt).toSuccess
        case NamedAst.Pattern.Str(s, loc) => (Substitution.empty, Type.Str).toSuccess

        case NamedAst.Pattern.Tag(enum, tag, p1, tvar, loc) =>
          visitPat(p1, tenv) map {
            case (subst, tpe) =>
              (subst, Type.Enum(???, ???))
          }

        case NamedAst.Pattern.Tuple(pats, tvar, loc) =>
          val elmsVal = @@(pats.map(p => visitPat(p, tenv)))
          elmsVal flatMap {
            case elms =>
              val substs = elms.map(_._1)
              val tpes = elms.map(_._2)
              val resultType = Type.mkFTuple(tpes)
              unify(tvar, resultType) flatMap {
                case subst => mergeAll(subst :: substs) map {
                  case resultSubst => (resultSubst, resultType)
                }
              }

          }

        case NamedAst.Pattern.FNone(tvar, loc) => ???
        case NamedAst.Pattern.FSome(pat, tvar, loc) => ???
        case NamedAst.Pattern.FNil(tvar, loc) => ???
        case NamedAst.Pattern.FList(hd, tl, tvar, loc) => ???
        case NamedAst.Pattern.FVec(elms, rest, tvar, loc) => ???
        case NamedAst.Pattern.FSet(elms, rest, tvar, loc) => ???
        case NamedAst.Pattern.FMap(elms, rest, tvar, loc) => ???
      }


      // TODO: Need to create initial type environment from defn

      visitExp(exp0)

    }
  }


  /**
    * Returns the most general unifier of the two given types `tpe1` and `tpe2`.
    *
    * Returns [[Failure]] if the two types cannot be unified.
    */
  def unify(tpe1: Type, tpe2: Type): Validation[Substitution, TypeError] = (tpe1, tpe2) match {
    case (x: Type.Var, _) => unifyVar(x, tpe2)
    case (_, x: Type.Var) => unifyVar(x, tpe1)
    case (Type.Unit, Type.Unit) => Substitution.empty.toSuccess
    case (Type.Bool, Type.Bool) => Substitution.empty.toSuccess
    case (Type.Char, Type.Char) => Substitution.empty.toSuccess
    case (Type.Float32, Type.Float32) => Substitution.empty.toSuccess
    case (Type.Float64, Type.Float64) => Substitution.empty.toSuccess
    case (Type.Int8, Type.Int8) => Substitution.empty.toSuccess
    case (Type.Int16, Type.Int16) => Substitution.empty.toSuccess
    case (Type.Int32, Type.Int32) => Substitution.empty.toSuccess
    case (Type.Int64, Type.Int64) => Substitution.empty.toSuccess
    case (Type.BigInt, Type.BigInt) => Substitution.empty.toSuccess
    case (Type.Str, Type.Str) => Substitution.empty.toSuccess
    case (Type.Native, Type.Native) => Substitution.empty.toSuccess
    case (Type.Arrow, Type.Arrow) => Substitution.empty.toSuccess
    case (Type.FTuple(l1), Type.FTuple(l2)) if l1 == l2 => Substitution.empty.toSuccess
    case (Type.FOpt, Type.FOpt) => Substitution.empty.toSuccess
    case (Type.FList, Type.FList) => Substitution.empty.toSuccess
    case (Type.FVec, Type.FVec) => Substitution.empty.toSuccess
    case (Type.FSet, Type.FSet) => Substitution.empty.toSuccess
    case (Type.FMap, Type.FMap) => Substitution.empty.toSuccess
    case (Type.Enum(name1, cases1), Type.Enum(name2, cases2)) if name1 == name2 =>
      val ts1 = cases1.values.toList
      val ts2 = cases2.values.toList
      unify(ts1, ts2)

    case (Type.Apply(t11, t12), Type.Apply(t21, t22)) =>
      unify(t11, t21) flatMap {
        case subst1 => unify(subst1(t12), subst1(t22)) map {
          case subst2 => subst2 @@ subst1
        }
      }

    // TODO: Remove once tags are gone:
    case (Type.Tag(_, _, t1), Type.Tag(_, _, t2)) => unify(t1, t2)

    case _ => TypeError.UnificationError(tpe1, tpe2).toFailure
  }

  /**
    * Unifies the two given lists of types `ts1` and `ts2`.
    */
  def unify(ts1: List[Type], ts2: List[Type]): Validation[Substitution, TypeError] = (ts1, ts2) match {
    case (Nil, Nil) => Substitution.empty.toSuccess
    case (tpe1 :: rs1, tpe2 :: rs2) => unify(tpe1, tpe2) flatMap {
      case subst1 => unify(subst1(rs1), subst1(rs2)) map {
        case subst2 => subst2 @@ subst1
      }
    }
    case _ => throw InternalCompilerException(s"Mismatched type lists: `$ts1' and `$ts2'.")
  }

  /**
    * Unifies the given variable `x` with the given type `tpe`.
    *
    * Performs the so-called occurs-check to ensure that the substitution is kind-preserving.
    */
  def unifyVar(x: Type.Var, tpe: Type): Validation[Substitution, TypeError] = {
    if (x == tpe) {
      return Substitution.empty.toSuccess
    }
    if (tpe.typeVars contains x) {
      return TypeError.OccursCheck().toFailure
    }
    if (x.kind != tpe.kind) {
      return TypeError.KindError().toFailure
    }
    return Substitution.singleton(x, tpe).toSuccess
  }

  /**
    * Reassembles the given expression `exp0` with the given substitution `subst0`.
    */
  def reassemble(exp0: NamedAst.Expression, subst0: Substitution): TypedAst.Expression = exp0 match {
    /*
     * Wildcard expression.
     */
    case NamedAst.Expression.Wild(tvar, loc) => ??? // TODO: TypedAst.Expression.Wild(subst0(tvar), loc)

    /*
     * Variable expression.
     */
    case NamedAst.Expression.Var(sym, tvar, loc) => TypedAst.Expression.Var(sym.toIdent, subst0(tvar), loc)

    /*
     * Reference expression.
     */
    case NamedAst.Expression.Ref(ref, tvar, loc) => TypedAst.Expression.Ref(???, subst0(tvar), loc) // TODO: What symbol?

    /*
     * Literal expression.
     */
    case NamedAst.Expression.Unit(loc) => TypedAst.Expression.Unit(loc)
    case NamedAst.Expression.True(loc) => TypedAst.Expression.True(loc)
    case NamedAst.Expression.False(loc) => TypedAst.Expression.False(loc)
    case NamedAst.Expression.Char(lit, loc) => TypedAst.Expression.Char(lit, loc)
    case NamedAst.Expression.Float32(lit, loc) => TypedAst.Expression.Float32(lit, loc)
    case NamedAst.Expression.Float64(lit, loc) => TypedAst.Expression.Float64(lit, loc)
    case NamedAst.Expression.Int8(lit, loc) => TypedAst.Expression.Int8(lit, loc)
    case NamedAst.Expression.Int16(lit, loc) => TypedAst.Expression.Int16(lit, loc)
    case NamedAst.Expression.Int32(lit, loc) => TypedAst.Expression.Int32(lit, loc)
    case NamedAst.Expression.Int64(lit, loc) => TypedAst.Expression.Int64(lit, loc)
    case NamedAst.Expression.BigInt(lit, loc) => TypedAst.Expression.BigInt(lit, loc)
    case NamedAst.Expression.Str(lit, loc) => TypedAst.Expression.Str(lit, loc)

    /*
     * Apply expression.
     */
    case NamedAst.Expression.Apply(lambda, args, tvar, loc) => ??? // TODO

    /*
     * Lambda expression.
     */
    case NamedAst.Expression.Lambda(params, exp, tvar, loc) => ??? // TODO

    /*
     * Unary expression.
     */
    case NamedAst.Expression.Unary(op, exp, tvar, loc) =>
      val e = reassemble(exp, subst0)
      TypedAst.Expression.Unary(op, e, subst0(tvar), loc)

    /*
     * Binary expression.
     */
    case NamedAst.Expression.Binary(op, exp1, exp2, tvar, loc) =>
      val e1 = reassemble(exp1, subst0)
      val e2 = reassemble(exp2, subst0)
      TypedAst.Expression.Binary(op, e1, e2, subst0(tvar), loc)

    /*
     * If-then-else expression.
     */
    case NamedAst.Expression.IfThenElse(exp1, exp2, exp3, tvar, loc) =>
      val e1 = reassemble(exp1, subst0)
      val e2 = reassemble(exp2, subst0)
      val e3 = reassemble(exp3, subst0)
      TypedAst.Expression.IfThenElse(e1, e2, e3, subst0(tvar), loc)

    /*
     * Let expression.
     */
    case NamedAst.Expression.Let(sym, exp1, exp2, tvar, loc) => ??? // TODO

    /*
     * Match expression.
     */
    case NamedAst.Expression.Match(exp, rules, tvar, loc) => ??? // TODO

    /*
     * Switch expression.
     */
    case NamedAst.Expression.Switch(rules, tvar, loc) =>
      val rs = rules.map {
        case (cond, body) => (reassemble(cond, subst0), reassemble(body, subst0))
      }
      TypedAst.Expression.Switch(rs, subst0(tvar), loc)

    /*
     * Tag expression.
     */
    case NamedAst.Expression.Tag(enum, tag, exp, tvar, loc) =>
      val e = reassemble(exp, subst0)
      TypedAst.Expression.Tag(???, tag, e, subst0(tvar), loc) // TODO what symbol?

    /*
     * Tuple expression.
     */
    case NamedAst.Expression.Tuple(elms, tvar, loc) =>
      val es = elms.map(e => reassemble(e, subst0))
      TypedAst.Expression.Tuple(es, subst0(tvar), loc)

    /*
     * None expression.
     */
    case NamedAst.Expression.FNone(tvar, loc) =>
      TypedAst.Expression.FNone(subst0(tvar), loc)

    /*
     * Some expression.
     */
    case NamedAst.Expression.FSome(exp, tvar, loc) =>
      val e = reassemble(exp, subst0)
      TypedAst.Expression.FSome(e, subst0(tvar), loc)

    /*
     * Nil expression.
     */
    case NamedAst.Expression.FNil(tvar, loc) =>
      TypedAst.Expression.FNil(subst0(tvar), loc)

    /*
     * List expression.
     */
    case NamedAst.Expression.FList(hd, tl, tvar, loc) =>
      val e1 = reassemble(hd, subst0)
      val e2 = reassemble(tl, subst0)
      TypedAst.Expression.FList(e1, e2, subst0(tvar), loc)

    /*
     * Vec expression.
     */
    case NamedAst.Expression.FVec(elms, tvar, loc) =>
      val es = elms.map(e => reassemble(e, subst0))
      TypedAst.Expression.FVec(es, subst0(tvar), loc)

    /*
     * Set expression.
     */
    case NamedAst.Expression.FSet(elms, tvar, loc) =>
      val es = elms.map(e => reassemble(e, subst0))
      TypedAst.Expression.FSet(es, subst0(tvar), loc)

    /*
     * Map expression.
     */
    case NamedAst.Expression.FMap(elms, tvar, loc) =>
      val es = elms map {
        case (key, value) => (reassemble(key, subst0), reassemble(value, subst0))
      }
      TypedAst.Expression.FMap(es, subst0(tvar), loc)

    /*
     * GetIndex expression.
     */
    case NamedAst.Expression.GetIndex(exp1, exp2, tvar, loc) =>
      val e1 = reassemble(exp1, subst0)
      val e2 = reassemble(exp2, subst0)
      TypedAst.Expression.GetIndex(e1, e2, subst0(tvar), loc)

    /*
     * PutIndex expression.
     */
    case NamedAst.Expression.PutIndex(exp1, exp2, exp3, tvar, loc) =>
      val e1 = reassemble(exp1, subst0)
      val e2 = reassemble(exp2, subst0)
      val e3 = reassemble(exp3, subst0)
      TypedAst.Expression.PutIndex(e1, e2, e3, subst0(tvar), loc)

    /*
     * Existential expression.
     */
    case NamedAst.Expression.Existential(params, exp, loc) =>
      val e = reassemble(exp, subst0)
      TypedAst.Expression.Existential(compat(params, subst0), e, loc)

    /*
     * Universal expression.
     */
    case NamedAst.Expression.Universal(params, exp, loc) =>
      val e = reassemble(exp, subst0)
      TypedAst.Expression.Universal(compat(params, subst0), e, loc)

    /*
     * Ascribe expression.
     */
    case NamedAst.Expression.Ascribe(exp, tpe, loc) =>
      // simply reassemble the nested expression.
      reassemble(exp, subst0)

    /*
     * User Error expression.
     */
    case NamedAst.Expression.UserError(tvar, loc) =>
      TypedAst.Expression.Error(subst0(tvar), loc)
  }


  private def compat(ps: List[NamedAst.FormalParam], subst: Substitution): List[Ast.FormalParam] = ps map {
    case NamedAst.FormalParam(sym, tpe) => Ast.FormalParam(sym.toIdent, subst(tpe))
  }

}
