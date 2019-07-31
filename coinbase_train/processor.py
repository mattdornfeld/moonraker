"""Summary
"""
from typing import List

import numpy as np
from rl.core import Processor

class CoinbaseEnvironmentProcessor(Processor):

    """This class takes output state from Environment.step
    and converts it to a form that can be processed by the
    actor and critic models.
    """

    def process_state_batch(self, batch: List[List[np.ndarray]]) -> List[np.ndarray]:
        """process_state_batch [summary]

        Args:
            batch (List[List[np.ndarray]]): [description]

        Returns:
            List[np.ndarray]: [description]
        """

        batched_account_funds = np.vstack([np.expand_dims(array, axis=0) for
                                           array in [a[0][0] for a in batch]])

        batched_order_books = np.vstack([np.expand_dims(a[0][1], axis=0) for a in batch])

        batched_time_series = np.vstack([np.expand_dims(array, axis=0) for 
                                         array in [a[0][2] for a in batch]])

        return [batched_account_funds,
                batched_order_books,
                batched_time_series]
