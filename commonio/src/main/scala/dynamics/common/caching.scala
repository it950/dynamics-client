// Copyright (c) 2017 The Trapelo Group LLC
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dynamics
package common

import scala.scalajs.js
import js._
import js.annotation._
import io.scalajs.nodejs.events.IEventEmitter
import cats.effect._

class NodeCacheOptions(
    /** In seconds. Default is 0. */
    val stdTTL: UndefOr[Int] = js.undefined,
    /** Delete interval. */
    val checkperiod: UndefOr[Int] = js.undefined,
    val errorOnMissing: UndefOr[Boolean] = js.undefined,
    /** Clone the returned value, vs just give it back directly. */
    val useClones: UndefOr[Boolean] = js.undefined
) extends js.Object

@js.native
@JSImport("node-cache", JSImport.Namespace)
class NodeCache() extends IEventEmitter {
  def this(opts: NodeCacheOptions) = this()
  // sync
  def set(key: String, obj: scala.Any): Unit = js.native
  // sync
  def set(key: String, obj: scala.Any, ttl: Int): Unit = js.native
  // sync
  def get[A](key: String): UndefOr[A] = js.native
  // sync
  def mget(keys: js.Array[String]): js.Dictionary[scala.Any] = js.native
  // sync
  def delete(key: String): Unit = js.native
  // sync
  def mdelete(keys: js.Array[String]): Unit = js.native
  // sync
  def keys(): js.Array[String]       = js.native
  def getStats(): js.Dictionary[Int] = js.native
  def close(): Unit                  = js.native
}

case class NodeCacheOps(cache: NodeCache) {
  //def set(): Unit = cache.on("set", js.undefined)
  //def del(): Unit = cache.on("del", js.undefined)
  //def expired(): Unit = cache.on("expired", js.undefined)
  def flush(cb: js.Function0[Unit]): Unit = cache.on("flush", cb)
}

trait NodeCacheSyntax {
  implicit def nodeCacheOps(cache: NodeCache) = NodeCacheOps(cache)
}

import scalacache.serialization.{Codec, InMemoryRepr}
import scalacache.{Cache, LoggingSupport}
import scala.concurrent.Future
import scala.concurrent.duration.Duration

class ScalaCacheNodeCache(underlying: NodeCache) extends Cache[InMemoryRepr] {

  override def remove(key: String) = Future.successful(underlying.delete(key))

  override def get[V](key: String)(implicit codec: Codec[V, InMemoryRepr]) = {
    val result = {
      val elem = underlying.get(key)
      if (elem == null || elem == js.undefined) None
      else Some(elem.asInstanceOf[V])
    }
    Future.successful(result)
  }

  override def put[V](key: String, value: V, ttl: Option[Duration])(implicit codec: Codec[V, InMemoryRepr]) = {
    underlying.set(key, value, ttl.map(_.toSeconds.toInt).getOrElse(0))
    Future.successful(())
  }

  override def removeAll() = Future.successful(underlying.mdelete(underlying.keys()))

  def close() = underlying.close()

}

import fs2._
import io.scalajs.nodejs
import io.scalajs.nodejs.fs
import io.scalajs.nodejs.fs.Fs
import io.scalajs.nodejs.buffer.Buffer

trait OfflineCache {
  def close(): IO[Unit]
  def flush(): IO[Unit]
  def write_(k: String): Unit
  def contains_(k: String): Boolean
  def write: Sink[IO, String]
  def contains: Pipe[IO, String, (Boolean, String)]
  def stats: (Int, Int) // writes, hits
}

case class NeverInCache() extends OfflineCache {
  def close(): IO[Unit]             = IO(())
  def flush(): IO[Unit]             = IO(())
  def write_(k: String): Unit       = {}
  def contains_(k: String): Boolean = false
  def write: Sink[IO, String] =
    _.evalMap { line =>
      IO.pure(())
    }
  def contains: Pipe[IO, String, (Boolean, String)] =
    _.map { line =>
      (false, line)
    }
  def stats = (0, 0)
}

case class LineCache(filename: String) extends OfflineCache {

  protected val counter = new java.util.concurrent.atomic.AtomicInteger(0)
  protected val hits    = new java.util.concurrent.atomic.AtomicInteger(0)

  def close(): IO[Unit] = flush().map(_ => Fs.closeSync(fd))

  def flush(): IO[Unit] = IO(())

  protected var cache: Set[String]        = Set()
  protected var fd: nodejs.FileDescriptor = _
  protected def init(): Unit = {
    try {
      cache = IOUtils.slurp(filename: String).split("\n").toSet
    } catch {
      case scala.util.control.NonFatal(_) => Set()
    }
    fd = Fs.openSync(filename, "a+")
  }

  init()

  def write_(key: String): Unit = {
    Fs.writeSync(fd, new Buffer(key + "\n"))
    counter.getAndIncrement()
  }

  def stats = (counter.get(), hits.get())

  def contains_(key: String): Boolean = {
    val r = cache.contains(key)
    if (r) hits.getAndIncrement()
    r
  }

  def write: Sink[IO, String] = _.evalMap { key =>
    IO { write_(key); () }
  }

  def contains: Pipe[IO, String, (Boolean, String)] =
    _.map { key =>
      if (contains_(key)) (true, key) else (false, key)
    }
}

/**
  * Manage a backing cache file.
  *
  * @param fname Generate a filename for the  file cache.
  * @param ignore Whether the file cache should be ignored and reacquired.
  *
  */
abstract class FileCache(fname: => String, ignore: => Boolean) {

  /** Get the content for the cache. */
  protected def getContent(): IO[String]

  private var content: String = null

  /** Ensure the cache exists via a potential download and return the content. */
  protected def put(content: String): Unit = {
    IOUtils.writeToFileSync(fname, content)
  }

  /** Return the offline cache potentially reading from disk or calling getContent().
    * The cache content is accessed/created when the return value is run.
    */
  def get(): IO[String] = {
    if (content != null)
      IO.pure(content)
    else if (IOUtils.fexists(fname) && !ignore && content == null) {
      IO {
        content = IOUtils.slurp(fname)
        content
      }
    } else
      get().map { content =>
        put(content); content
      }
  }

}
