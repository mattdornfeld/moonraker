"""Summary
"""
from dataclasses import dataclass
from datetime import datetime, timedelta
from queue import Empty as EmptyException, Full as FullException
from threading import Event, Thread
from typing import List

from sqlalchemy.orm import sessionmaker

from fakebase import constants as c
from fakebase.orm import CoinbaseOrder, CoinbaseCancellation
from fakebase.utils.queue_utils import PriorityQueue, populate_datetime_queue
from fakebase.types import ProductId


@dataclass
class Result:
    """
    Results contains the CoinbaseCancellation and CoinbaseOrder objects for
    a given time interval
    """

    cancellations: List[CoinbaseCancellation]
    orders: List[CoinbaseOrder]


class DatabaseWorkers:

    """Summary
    """

    def __init__(
        self,
        end_dt: datetime,
        num_workers: int,
        product_id: ProductId,
        results_queue_size: int,
        start_dt: datetime,
        time_delta: timedelta,
    ):
        """Summary

        Args:
            end_dt (datetime): Description
            num_workers (int): Description
            product_id (ProductId): Description
            results_queue_size (int): Description
            start_dt (datetime): Description
            time_delta (timedelta): Description
        """
        self._dt_queue = populate_datetime_queue(start_dt, end_dt, time_delta)
        self._product_id = product_id
        self._stop_workers = Event()
        self._time_delta = time_delta
        self._workers: List[Thread] = []
        self._Session = sessionmaker(bind=c.ENGINE)  # pylint: disable=C0103
        self.results_queue: PriorityQueue[datetime, Result] = PriorityQueue(
            maxsize=results_queue_size
        )

        for _ in range(num_workers):
            worker = Thread(target=self.start_worker)
            self._workers.append(worker)
            worker.daemon = True
            worker.start()

    def _query_from_db(
        self, interval_end_dt: datetime, interval_start_dt: datetime
    ) -> Result:
        """Summary

        Args:
            interval_end_dt (datetime): Description
            interval_start_dt (datetime): Description

        Returns:
            Result: Description
        """
        sess = self._Session()

        orders: List[CoinbaseOrder] = (
            sess.query(CoinbaseOrder)
            .filter(CoinbaseOrder.time.between(interval_start_dt, interval_end_dt))
            .filter(CoinbaseOrder.product_id == str(self._product_id))
            .all()
        )

        cancellations: List[CoinbaseCancellation] = (
            sess.query(CoinbaseCancellation)
            .filter(
                CoinbaseCancellation.time.between(interval_start_dt, interval_end_dt)
            )
            .filter(CoinbaseCancellation.product_id == str(self._product_id))
            .all()
        )

        sess.close()

        return Result(cancellations=cancellations, orders=orders)

    def start_worker(self) -> None:
        """Summary
        """
        while True:
            try:
                interval_start_dt = self._dt_queue.get(block=False)
            except EmptyException:
                break

            interval_end_dt = interval_start_dt + self._time_delta

            results = self._query_from_db(
                interval_end_dt=interval_end_dt, interval_start_dt=interval_start_dt
            )

            while True:
                if self._stop_workers.is_set():
                    return

                try:
                    self.results_queue.put((interval_start_dt, results), block=False)
                    break
                except FullException:
                    continue

    def stop_workers(self) -> None:
        """Summary
        """
        self._stop_workers.set()
