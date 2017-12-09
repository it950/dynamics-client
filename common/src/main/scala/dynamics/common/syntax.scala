// Copyright (c) 2017 aappddeevv@gmail.com
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dynamics
package common

import scala.scalajs.js
import js._
import JSConverters._
import io.scalajs.nodejs._
import scala.concurrent._
import io.scalajs.util.PromiseHelper.Implicits._
import fs2._
import cats._
import cats.data._
import cats.implicits._
import fs2.interop.cats._
import io.scalajs.npm.chalk._
import js.Dynamic.{literal => jsobj}

final case class JsAnyOps(a: js.Any) {
  def asJsObj: js.Object        = a.asInstanceOf[js.Object]
  def asDyn: js.Dynamic         = a.asInstanceOf[js.Dynamic]
  def asString: String          = a.asInstanceOf[String]
  def asNumer: Number           = a.asInstanceOf[Number]
  def asInt: Int                = a.asInstanceOf[Int]
  def asDouble: Double          = a.asInstanceOf[Double]
  def asBoolean: Boolean        = a.asInstanceOf[Boolean]
  def asJsArray[A]: js.Array[A] = a.asInstanceOf[js.Array[A]]
}

trait JsAnySyntax {
  implicit def jsAnyOpsSyntax(a: js.Any) = new JsAnyOps(a)
}

final case class JsObjectOps(o: js.Object) {
  def asDict[A] = o.asInstanceOf[js.Dictionary[A]]
  def asDyn     = o.asInstanceOf[js.Dynamic]
}

final case class JsDictionaryOps(o: js.Dictionary[_]) {
  def asJsObj = o.asInstanceOf[js.Object]
  def asDyn   = o.asInstanceOf[js.Dynamic]
}

trait JsObjectSyntax {
  implicit def jsObjectOpsSyntax(a: js.Object)           = new JsObjectOps(a)
  implicit def jsDictonaryOpsSyntax(a: js.Dictionary[_]) = new JsDictionaryOps(a)
}

final case class JsUndefOrStringOps(a: UndefOr[String]) {
  def orEmpty: String = a.getOrElse("")
}

/** Not sure this is really going to do much for me. */
final case class JsUndefOrOps[A](a: UndefOr[A]) {
  def isNull  = a == null
  def isEmpty = isNull || !a.isDefined
}

trait JsUndefOrSyntax {
  implicit def jsUndefOrOpsSyntax[A](a: UndefOr[A])   = JsUndefOrOps(a)
  implicit def jsUndefOrStringOps(a: UndefOr[String]) = JsUndefOrStringOps(a)
}

final case class JsDynamicOps(val jsdyn: js.Dynamic) {
  def asString: String            = jsdyn.asInstanceOf[String]
  def asInt: Int                  = jsdyn.asInstanceOf[Int]
  def asArray[A]: js.Array[A]     = jsdyn.asInstanceOf[js.Array[A]]
  def asBoolean: Boolean          = jsdyn.asInstanceOf[Boolean]
  def asJSObj: js.Object          = jsdyn.asInstanceOf[js.Object]
  def asDict[A]: js.Dictionary[A] = jsdyn.asInstanceOf[js.Dictionary[A]]
  def asUndefOr[A]: js.UndefOr[A] = jsdyn.asInstanceOf[js.UndefOr[A]]
  def asJsObjSub[A <: js.Object]  = jsdyn.asInstanceOf[A] // assumes its there!
  def asJsArray[A <: js.Object]   = jsdyn.asInstanceOf[js.Array[A]]
}

trait JsDynamicSyntax {
  implicit def jsDynamicOpsSyntax(jsdyn: js.Dynamic) = JsDynamicOps(jsdyn)
}

object NPMTypes {
  type JSCallbackNPM[A] = js.Function2[io.scalajs.nodejs.Error, A, scala.Any] => Unit
  type JSCallback[A]    = js.Function2[js.Error, A, scala.Any] => Unit

  /** This does not work as well as I thought it would... */
  def callbackToTask[A](f: JSCallbackNPM[A])(implicit s: Strategy): Task[A] = JSCallbackOpsNPM(f).toTask
}

import NPMTypes._

final case class JSCallbackOpsNPM[A](val f: JSCallbackNPM[A]) {

  import scala.scalajs.runtime.wrapJavaScriptException

  /** Convert a standard (err, a) callback to a Task. */
  def toTask(implicit s: Strategy) =
    Task.async { (cb: (Either[Throwable, A] => Unit)) =>
      f((err, a) => {
        if (err == null || js.isUndefined(err)) cb(Right(a))
        else cb(Left(wrapJavaScriptException(err)))
      })
    }
}

trait JSCallbackSyntaxNPM {
  implicit def jsCallbackOpsSyntaxNPM[A](f: JSCallbackNPM[A])(implicit s: Strategy) = JSCallbackOpsNPM(f)
}

/** These are not all implicits. FIXME */
trait MiscImplicits {
  import scala.scalajs.runtime.wrapJavaScriptException

  /** Show JSON in its rawness form. Use PrettyJson. for better looking JSON. */
  implicit def showJsObject[A <: js.Object] = Show.show[A] { obj =>
    val sb = new StringBuilder()
    sb.append(Utils.pprint(obj))
    sb.toString
  }

  /** Convert a js.Promise to a fs2.Task. */
  implicit class RichPromise[A](p: js.Promise[A]) {
    def toTask(implicit S: Strategy): Task[A] = {
      val t: Task[A] = Task.async { cb =>
        p.`then`[Unit](
          { (v: A) =>
            cb(Right(v))
          },
          js.defined { (e: scala.Any) =>
            // create a Throwable from e
            val t = e match {
              case th: Throwable => th
              case _             => js.JavaScriptException(e)
            }
            cb(Left(t))
          }
        )
        () // return unit
      }
      t
    }
  }
}

case class FutureOps[A](val f: Future[A])(implicit val s: Strategy, ec: ExecutionContext) {
  def toTask: Task[A] = Task.fromFuture(f)
}

trait FutureSyntax {
  implicit def futureToTask[A](f: Future[A])(implicit s: Strategy, ec: ExecutionContext) = FutureOps[A](f)
}

// Add each individual syntax trait to this
trait AllSyntax
    extends JsDynamicSyntax
    with JSCallbackSyntaxNPM
    with JsUndefOrSyntax
    with JsObjectSyntax
    with JsAnySyntax
    with FutureSyntax

// Add each individal syntax trait to this
object syntax {
  object all           extends AllSyntax
  object jsdynamic     extends JsDynamicSyntax
  object jscallbacknpm extends JSCallbackSyntaxNPM
  object jsundefor     extends JsUndefOrSyntax
  object jsobject      extends JsObjectSyntax
  object jsany         extends JsAnySyntax
  object future        extends FutureSyntax
}

trait AllImplicits extends MiscImplicits

object implicits extends AllImplicits with AllSyntax
