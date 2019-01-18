"""Summary
"""
from queue import Empty as EmptyException, PriorityQueue
from threading import Thread

from sqlalchemy.orm import sessionmaker

from coinbase_train.fakebase import constants as c
from coinbase_train.fakebase import utils
from coinbase_train.fakebase.orm import CoinbaseOrder

class DatabaseWorkers:

    """Summary
    
    Attributes:
        dt_queue (Queue[datetime]): Description
        results_queue (PriorityQueue[datetime, List[CoinbaseOrder]]): Description
        Session (TYPE): Description
        time_delta (timedelta): Description
        workers (List[Thread]): Description
    """
    
    def __init__(self, end_dt, num_workers, start_dt, time_delta):
        """Summary
        
        Args:
            end_dt (datetime): Description
            num_workers (int): Description
            start_dt (datetime): Description
            time_delta (timedelta): Description
        """
        self.dt_queue = utils.populate_datetime_queue(start_dt, end_dt, time_delta)        
        self.time_delta = time_delta
        self.results_queue = PriorityQueue()

        self.Session = sessionmaker(bind=c.ENGINE) #pylint: disable=C0103

        self.workers = []
        for _ in range(num_workers):
            worker = Thread(target=self.start_worker)
            self.workers.append(worker)
            worker.daemon = True
            worker.start()

    def _query_from_db(self, interval_end_dt, interval_start_dt):
        """Summary
        
        Args:
            interval_end_dt (datetime): Description
            interval_start_dt (datetime): Description
        
        Returns:
            List[CoinbaseOrder]: Description
        """
        sess = self.Session()

        orders = (
            sess
            .query(CoinbaseOrder)
            .filter(CoinbaseOrder.time.between(interval_start_dt, interval_end_dt))
            .order_by(CoinbaseOrder.time.asc()) #pylint: disable=E1101
            .all()
            )

        sess.close()

        return orders

    def start_worker(self):
        """Summary
        """
        while True:
            try:
                interval_start_dt = self.dt_queue.get(block=False)
            except EmptyException:
                break

            interval_end_dt = interval_start_dt + self.time_delta

            results = self._query_from_db(
                interval_end_dt=interval_end_dt, 
                interval_start_dt=interval_start_dt)

            self.results_queue.put((interval_start_dt, results))
