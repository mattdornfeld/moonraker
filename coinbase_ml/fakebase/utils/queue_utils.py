"""
 [summary]

Returns:
    [type]: [description]
"""
from datetime import datetime, timedelta
from queue import PriorityQueue as BasePriorityQueue, Queue as BaseQueue
from typing import Generic, Optional, Tuple, TypeVar

Key = TypeVar("Key")
Value = TypeVar("Value")

# pylint: disable=useless-super-delegation
class PriorityQueue(BasePriorityQueue, Generic[Key, Value]):
    """
    PriorityQueue is a generically typed interface to queue.PriorityQueue with the
    addition of a peek method
    """

    def get(
        self, block: bool = True, timeout: Optional[float] = None
    ) -> Tuple[Key, Value]:
        """
        PriorityQueue [summary]

        Args:
            block (bool, optional): [description]. Defaults to True.
            timeout (Optional[float], optional): [description]. Defaults to None.

        Returns:
            Tuple[Key, Value]: [description]
        """
        return super().get(block, timeout)

    def peek(self) -> Tuple[Key, Value]:
        """
        peek [summary]

        Returns:
            Tuple[Key, Value]: [description]
        """
        return self.queue[0]

    def put(
        self,
        item: Tuple[Key, Value],
        block: bool = True,
        timeout: Optional[float] = None,
    ) -> None:
        """
        put [summary]

        Args:
            item (Tuple[Key, Value]): [description]
            block (bool, optional): [description]. Defaults to True.
            timeout (Optional[float], optional): [description]. Defaults to None.
        """
        super().put(item, block, timeout)


class Queue(BaseQueue, Generic[Value]):
    """
    Queue is a generically typed interface to queue.Queue
    """

    def get(self, block: bool = True, timeout: Optional[float] = None) -> Value:
        """
        Queue [summary]

        Args:
            block (bool, optional): [description]. Defaults to True.
            timeout (Optional[float], optional): [description]. Defaults to None.

        Returns:
            Value: [description]
        """
        return super().get(block, timeout)

    def put(
        self, item: Value, block: bool = True, timeout: Optional[float] = None
    ) -> None:
        """
        put [summary]

        Args:
            item (Value): [description]
            block (bool, optional): [description]. Defaults to True.
            timeout (Optional[float], optional): [description]. Defaults to None.
        """
        super().put(item, block, timeout)


def populate_datetime_queue(
    start_dt: datetime,
    end_dt: datetime,
    time_delta: timedelta,
    dt_queue: Queue[datetime] = None,
) -> Queue[datetime]:
    """
    populate_datetime_queue [summary]

    Args:
        start_dt (datetime): [description]
        end_dt (datetime): [description]
        time_delta (timedelta): [description]
        dt_queue (Queue[datetime], optional): [description]. Defaults to None.

    Returns:
        Queue[datetime]: [description]
    """
    if dt_queue is None:
        dt_queue = Queue()

    _datetime = start_dt

    while _datetime < end_dt:

        dt_queue.put(_datetime)

        _datetime += time_delta

    return dt_queue
