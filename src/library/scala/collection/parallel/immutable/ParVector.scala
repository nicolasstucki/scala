/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala
package collection
package parallel.immutable

import scala.collection.generic.{GenericParTemplate, CanCombineFrom, ParFactory}
import scala.collection.parallel.{ParSeqLike, Combiner, SeqSplitter}
import mutable.ArrayBuffer
import immutable.Vector
import immutable.VectorBuilder
import immutable.VectorIterator

/** Immutable parallel vectors, based on RRB vectors.
 *
 *  $paralleliterableinfo
 *
 *  $sideeffects
 *
 *  @tparam T    the element type of the vector
 *
 *  @author Aleksandar Prokopec
 *  @author Nicolas Stucki
 *  @since 2.9
 *  @see  [[http://docs.scala-lang.org/overviews/parallel-collections/concrete-parallel-collections.html#parallel_vector Scala's Parallel Collections Library overview]]
 *  @see [[http://infoscience.epfl.ch/record/205070/files/main.pdf?version=1 Turning Relaxed Radix Balanced Vector from Theory into Practice for Scala Collections]]
 *  @see  [[http://dl.acm.org/citation.cfm?doid=2784731.2784739 RRB Vectors]]
 *  section on `ParVector` for more information.
 *  @
 *
 *  @define Coll `immutable.ParVector`
 *  @define coll immutable parallel vector
 */
class ParVector[+T](private[this] val vector: Vector[T])
extends ParSeq[T]
   with GenericParTemplate[T, ParVector]
   with ParSeqLike[T, ParVector[T], Vector[T]]
   with Serializable
{
  override def companion = ParVector

  def this() = this(Vector())

  def apply(idx: Int) = vector.apply(idx)

  def length = vector.length

  def splitter: SeqSplitter[T] = {
    val pit = new ParVectorSplitter(0, vector.length)
    pit.initIteratorFrom(vector)
    pit
  }

  override def seq: Vector[T] = vector

  override def toVector: Vector[T] = vector

  class ParVectorSplitter(val _start: Int, val _end: Int)
      extends VectorIterator[T](_start, _end) with SeqSplitter[T] {

    override def remaining: Int = super.remaining

    def dup: SeqSplitter[T] = {
      val pit = new ParVectorSplitter(_end - remaining, _end)
      pit.initIteratorFrom(this)
      pit
    }

    def split: Seq[ParVectorSplitter] = {
      val rem = remaining
      if (rem >= 2) {
        val _half = rem / 2
        val _splitModulo =
          if (rem <= (1 << 5)) 1
          else if (rem <= (1 << 10)) 1 << 5
          else if (rem <= (1 << 15)) 1 << 10
          else if (rem <= (1 << 20)) 1 << 15
          else if (rem <= (1 << 25)) 1 << 20
          else 1 << 25
        val _halfAdjusted =
          if (_half > _splitModulo) _half - _half % _splitModulo
          else if (_splitModulo < _end) _splitModulo else _half
        return psplit(_halfAdjusted, rem - _halfAdjusted)
      } else {
        return Seq(this)
      }
    }

    def psplit(sizes: Int*): Seq[ParVectorSplitter] = {
      val splitted = new ArrayBuffer[ParVectorSplitter]
      var currentPos = _end - remaining
      for (sz <- sizes) {
        val pit = new ParVectorSplitter(currentPos, currentPos + sz)
        pit.initIteratorFrom(this)
        splitted += pit
        currentPos += sz
      }
      splitted
    }
  }

}

/** $factoryInfo
 *  @define Coll `immutable.ParVector`
 *  @define coll immutable parallel vector
 */
object ParVector extends ParFactory[ParVector] {
  implicit def canBuildFrom[T]: CanCombineFrom[Coll, T, ParVector[T]] =
    new GenericCanCombineFrom[T]

  def newBuilder[T]: Combiner[T, ParVector[T]] = newCombiner[T]

  def newCombiner[T]: Combiner[T, ParVector[T]] = new ParVectorCombiner[T]
}

private[immutable] class ParVectorCombiner[T] extends Combiner[T, ParVector[T]] {

  private[immutable] val builder: VectorBuilder[T] = new VectorBuilder[T]

  override def size = builder.endIndex

  override def result() = new ParVector[T](builder.result())

  override def clear() = builder.clear()

  override def +=(elem: T) = {
    builder += elem
    this
  }

  override def ++=(xs: TraversableOnce[T]) = {
    builder ++= xs
    this
  }

  def combine[U <: T, NewTo >: ParVector[T]](other: Combiner[U, NewTo]): Combiner[U, NewTo] = {
    if (this eq other)
      return this
    else {
      val newCombiner = new ParVectorCombiner[T]
      newCombiner ++= this.builder.result()
      newCombiner ++= other.asInstanceOf[ParVectorCombiner[T]].builder.result()
      return newCombiner
    }
  }
}
