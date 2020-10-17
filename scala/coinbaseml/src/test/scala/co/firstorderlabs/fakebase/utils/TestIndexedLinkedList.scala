package co.firstorderlabs.fakebase.utils

import org.scalatest.funspec.AnyFunSpec

class TestIndexedLinkedList extends AnyFunSpec {
  val expectedHead = (1, 2)
  val expectedMiddle = (2, 3)
  val expectedTail = (3, 4)
  val keyValues = List(expectedHead, expectedMiddle, expectedTail)

  describe("IndexedLinkedList") {
    it(
      "When one element is added to the list, that element should be the head and tail. The list should have size==1." +
        "The element should be retrievable with the get method."
    ) {
      val indexedLinkedList = new IndexedLinkedList[Int, Int]
      indexedLinkedList.addOne(expectedHead)
      val expectedNode =
        new indexedLinkedList.Node(expectedHead._1, expectedHead._2)
      assert(expectedNode equalTo indexedLinkedList.head.get)
      assert(expectedNode equalTo indexedLinkedList.tail.get)
      assert(indexedLinkedList.size == 1)
      assert(indexedLinkedList.get(expectedHead._1).get == expectedHead._2)
    }

    it(
      "When elements are removed from the head the next element should become the head. This should continue until the " +
        "the list is empty."
    ) {
      val indexedLinkedList = new IndexedLinkedList[Int, Int]
      indexedLinkedList.addAll(keyValues)

      assert(indexedLinkedList.head.get.getItem == expectedHead)
      assert(indexedLinkedList.tail.get.getItem == expectedTail)
      assert(indexedLinkedList.size == 3)

      indexedLinkedList.remove(expectedHead._1)
      assert(indexedLinkedList.head.get.getItem == expectedMiddle)
      assert(indexedLinkedList.tail.get.getItem == expectedTail)
      assert(indexedLinkedList.size == 2)

      indexedLinkedList.remove(expectedMiddle._1)
      assert(indexedLinkedList.head.get.getItem == expectedTail)
      assert(indexedLinkedList.tail.get.getItem == expectedTail)
      assert(indexedLinkedList.size == 1)

      indexedLinkedList.remove(expectedTail._1)
      assert(indexedLinkedList.head.isEmpty)
      assert(indexedLinkedList.tail.isEmpty)
      assert(indexedLinkedList.size == 0)
    }

    it(
      "When elements are removed from the tail the previous element should become the tail. This should continue until the " +
        "the list is empty."
    ) {
      val indexedLinkedList = new IndexedLinkedList[Int, Int]
      indexedLinkedList.addAll(keyValues)

      assert(indexedLinkedList.head.get.getItem == expectedHead)
      assert(indexedLinkedList.tail.get.getItem == expectedTail)
      assert(indexedLinkedList.size == 3)

      indexedLinkedList.remove(expectedTail._1)
      assert(indexedLinkedList.head.get.getItem == expectedHead)
      assert(indexedLinkedList.tail.get.getItem == expectedMiddle)
      assert(indexedLinkedList.size == 2)

      indexedLinkedList.remove(expectedMiddle._1)
      assert(indexedLinkedList.head.get.getItem == expectedHead)
      assert(indexedLinkedList.tail.get.getItem == expectedHead)
      assert(indexedLinkedList.size == 1)

      indexedLinkedList.remove(expectedHead._1)
      assert(indexedLinkedList.head.isEmpty)
      assert(indexedLinkedList.tail.isEmpty)
      assert(indexedLinkedList.size == 0)
    }

    it(
      "When an element is removed from the middle it should leave the head and tail unchanged but decrease the size of the list."
    ) {
      val indexedLinkedList = new IndexedLinkedList[Int, Int]
      indexedLinkedList.addAll(keyValues)

      assert(indexedLinkedList.head.get.getItem == expectedHead)
      assert(indexedLinkedList.tail.get.getItem == expectedTail)
      assert(indexedLinkedList.size == 3)

      indexedLinkedList.remove(expectedMiddle._1)
      assert(indexedLinkedList.head.get.getItem == expectedHead)
      assert(indexedLinkedList.tail.get.getItem == expectedTail)
      assert(indexedLinkedList.size == 2)
    }

    it("The iterator method should return items in the order they're added.") {
      val indexedLinkedList = new IndexedLinkedList[Int, Int]
      indexedLinkedList.addAll(keyValues)
      assert(
        indexedLinkedList.iterator
          .zip(keyValues)
          .map(i => i._1 == i._2)
          .forall(_ == true)
      )
    }

    it("The clear method should empty the list.") {
      val indexedLinkedList = new IndexedLinkedList[Int, Int]
      indexedLinkedList.addAll(keyValues)
      indexedLinkedList.clear
      println(indexedLinkedList.size == 0)
      println(indexedLinkedList.head.isEmpty)
      println(indexedLinkedList.tail.isEmpty)
    }

    it("Adding a duplicate key should throw a DuplicateKey exception") {
      val indexedLinkedList = new IndexedLinkedList[Int, Int]
      indexedLinkedList.addOne(expectedHead)
      assertThrows[DuplicateKey] { indexedLinkedList.addOne(expectedHead) }
    }
  }
}
