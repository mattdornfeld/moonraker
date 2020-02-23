"""
 [summary]
"""
from dataclasses import dataclass

import numpy as np

from coinbase_ml.common.action import ActionBase


@dataclass
class StateAtTime:
    """
    StateAtTime encapsulates the state of the exhange necessary
    for common.featurizers.Featurizer to perform its operations.
    This is the data type in the deque Featurizer.state_buffer.
    """

    account_funds: np.ndarray
    action: ActionBase
    buy_order_book: np.ndarray
    normalized_account_funds: np.ndarray
    sell_order_book: np.ndarray
    time_series: np.ndarray
