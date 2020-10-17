package co.firstorderlabs.fakebase.utils

import scala.collection.mutable

class DuplicateKey extends IllegalStateException

/**
  * A doubly linked list with an HashMap index. Supports constant
  * time tail insertion, lookup by index key, and random access removal by index key.
  * @tparam K
  * @tparam V
  */
class IndexedLinkedList[K, V] extends mutable.Growable[(K, V)] {
  private val index = new mutable.HashMap[K, Node[K, V]]
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
    if (index.contains(elem._1)) {
      throw new DuplicateKey
    } else {
      index.put(elem._1, node.get)
    }

    (_head, _tail) match {
      case (Some(head), Some(tail)) => {
        node.get.setParent(Some(tail))
        tail.setChild(node)
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
    index.get(key).map(_.getValue)

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
    var currentNode = head
    val _iterator = for (_ <- 1 to size) yield {
      val previousNode = currentNode.get
      currentNode = currentNode.get.getChild
      (previousNode.getKey, previousNode.getValue)
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
    val node = index.remove(key)
    node match {
      case Some(node) => {
        if (_head.get equalTo node) { _head = node.getChild }
        if (_tail.get equalTo node) { _tail = node.getParent }
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
  class Node[K, V](
      key: K,
      value: V,
      parent: Option[Node[K, V]] = None,
      child: Option[Node[K, V]] = None
  ) {
    private val _key = key
    private val _value = value
    private var _parent = parent
    private var _child = child

    def equalTo(that: Node[K, V]): Boolean =
      (_key == that.getKey
        && _value == that.getValue
        && _parent == that.getParent
        && _child == that.getChild)

    /**
      * Returns child Node
      * @return
      */
    def getChild: Option[Node[K, V]] = _child

    /**
      * Returns (key, value) tuple
      * @return
      */
    def getItem: (K, V) = (_key, _value)

    /**
      * Returns key
      * @return
      */
    def getKey: K = _key

    /**
      * Returns parent Node
      * @return
      */
    def getParent: Option[Node[K, V]] = _parent

    /**
      * Returns value contained by Node
      * @return
      */
    def getValue: V = _value

    /**
      * Removes node from IndexedLinkList
      * @return
      */
    def remove: Node[K, V] = {
      (_child, _parent) match {
        case (Some(_child), Some(_parent)) => {
          _child.setParent(Some(_parent))
          _parent.setChild(Some(_child))
        }
        case (Some(_child), None)  => _child.setParent(None)
        case (None, Some(_parent)) => _parent.setChild(None)
        case (None, None)          =>
      }

      this
    }

    def setChild(node: Option[Node[K, V]]): Unit = _child = node

    def setParent(node: Option[Node[K, V]]): Unit = _parent = node

    override def toString: String = s"Node<${value}>"
  }
}
