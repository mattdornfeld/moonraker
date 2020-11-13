package co.firstorderlabs.coinbaseml.fakebase.utils

import scala.collection.mutable

class DuplicateKey extends IllegalStateException

/**
  * A doubly linked list with an HashMap index. Supports constant
  * time tail insertion, lookup by index key, and random access removal by index key.
  * @tparam K
  * @tparam V
  */
class IndexedLinkedList[K, V] extends mutable.Growable[(K, V)] {
  private val index = new mutable.HashMap[Int, Node[K, V]]
  private var _head: Option[Node[K, V]] = None
  private var _tail: Option[Node[K, V]] = None

  /**
    * Adds value to tail of list and index it by key. If key is not unique
    * throws DuplicateKey exception.
    * @param elem
    */
  @throws[DuplicateKey]
  override def addOne(elem: (K, V)): this.type = {
    val node = Some(new Node(elem._1, elem._2))
    val hashCode = elem._1.hashCode
    if (index.contains(hashCode)) {
      throw new DuplicateKey
    } else {
      index.put(hashCode, node.get)
    }

    (_head, _tail) match {
      case (Some(head), Some(tail)) => {
        node.get.parent = Some(tail)
        tail.child = node
        _tail = node
      }
      case (None, None) => {
        _head = node
        _tail = node
      }
      case _ => throw new IllegalStateException
    }

    this
  }

  override def clear(): Unit = {
    index.clear
    _head = None
    _tail = None
  }

  /**
    * Returns true if that has same elements as this
    * @param that
    * @return
    */
  def equalTo(that: IndexedLinkedList[K, V]): Boolean =
    that.iterator.zip(iterator).forall(item => item._1 == item._2)

  /**
    * Get value by index key. Returns None if key is not in index.
    * @param key
    * @return
    */
  def get(key: K): Option[V] =
    index.get(key.hashCode).map(_.value)

  /**
    * Gets head Node of list. Returns None if list empty.
    * @return
    */
  def head: Option[Node[K, V]] = _head

  /**
    *
    * @return
    */
  def isEmpty: Boolean = index.isEmpty

  /**
    *
    * @return
    */
  def iterator: Iterator[(K, V)] = {
    var nextNode = head.map(_.clone)
    val _iterator = for (_ <- (1 to size).to(LazyList)) yield {
      val currentNode = nextNode.get
      nextNode = currentNode.child.map(_.clone)
      currentNode.child = nextNode
      nextNode.map(_.parent = Some(currentNode))
      (currentNode.key, currentNode.value)
    }
    _iterator.iterator
  }

  /**
    * Gets tail Node of list. Returns None if list empty.
    * @return
    */
  def tail: Option[Node[K, V]] = _tail

  /**
    * Lookups and removes value from list. Returns Node containing value if
    * successful. Returns None if value does not exist.
    * @param key
    * @return
    */
  def remove(key: K): Option[Node[K, V]] = {
    val node = index.remove(key.hashCode)
    node match {
      case Some(node) => {
        if (_head.get equalTo node) { _head = node.child }
        if (_tail.get equalTo node) { _tail = node.parent }
        Some(node.remove)
      }
      case None => None
    }
  }

  /**
    * Size of list
    * @return
    */
  def size: Int = index.size

  /**
    * Node of IndexedLinkedList
    * @param key
    * @param value
    * @param parent
    * @param child
    * @tparam K
    * @tparam V
    */
  case class Node[K, V](
      key: K,
      value: V,
      var parent: Option[Node[K, V]] = None,
      var child: Option[Node[K, V]] = None
  ) {

    /**
      *
      * @return
      */
    override def clone(): Node[K, V] = Node(key, value, parent, child)

    /**
      *
      * @param that
      * @return
      */
    def equalTo(that: Node[K, V]): Boolean = this == that

    /**
      * Returns (key, value) tuple
      * @return
      */
    def item: (K, V) = (key, value)

    /**
      * Removes node from IndexedLinkList
      * @return
      */
    def remove: Node[K, V] = {
      (child, parent) match {
        case (Some(child), Some(parent)) => {
          child.parent = Some(parent)
          parent.child = Some(child)
        }
        case (Some(child), None) => child.parent = None
        case (None, Some(parent)) => parent.child = None
        case (None, None) =>
      }

      this
    }

    override def toString: String = s"Node<${value}>"
  }
}
