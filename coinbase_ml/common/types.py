"""
 [summary]
"""
from dataclasses import dataclass
from typing import Deque, List, NewType

import numpy as np

from coinbase_ml.common.utils.time_utils import TimeInterval
from coinbase_ml.fakebase.orm import CoinbaseMatch


@dataclass
class StateAtTime:
    """
    StateAtTime encapsulates the state of the exhange necessary
    for common.featurizers.Featurizer to perform its operations.
    This is the data type in the deque Featurizer.state_buffer.
    """

    account_funds: np.ndarray
    account_matches: List[CoinbaseMatch]
    buy_order_book: np.ndarray
    normalized_account_funds: np.ndarray
    sell_order_book: np.ndarray
    time_interval: TimeInterval
    time_series: np.ndarray


StateBuffer = Deque[StateAtTime]
SimulationId = NewType("SimulationId", str)
