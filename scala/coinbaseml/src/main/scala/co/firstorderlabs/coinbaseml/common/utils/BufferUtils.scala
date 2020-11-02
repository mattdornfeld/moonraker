package co.firstorderlabs.coinbaseml.common.utils

import scala.collection.mutable

object BufferUtils {

  /** FiniteQueue is a generic mutable queue class with a maximum size. As new elements are enqueued old ones
    * are removed so that there are never more than maxSize elements in the queue. The last element of the queue is the
    * most recently inserted. The first element is the oldest inserted.

    * @param maxSize
    */
  class FiniteQueue[A](maxSize: Int) extends mutable.Queue[A] {
    private var _maxSize = maxSize

    /** Override addOne so that there are never more than maxSize elements in the queue
      *
      * @param elem
      * @return
      */
    override def addOne(elem: A): this.type = {
      if (length >= _maxSize) dequeue()
      super.addOne(elem)
      this
    }

    /** Get max size of the queue
      *
      * @return
      */
    def getMaxSize: Int = _maxSize

    /** Set max size of the queue
      *
      * @param maxSize
      */
    def setMaxSize(maxSize: Int): Unit = _maxSize = maxSize
  }
}
