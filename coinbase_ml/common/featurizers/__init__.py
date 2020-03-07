"""
 [summary]
"""
from copy import deepcopy
from dataclasses import dataclass
from typing import Deque, Dict, Generic, List, Optional, Type

import numpy as np

from coinbase_ml.common import constants as c
from coinbase_ml.common.action import ActionBase
from coinbase_ml.common.observations import Observation
from coinbase_ml.common.reward import BaseRewardStrategy
from coinbase_ml.common.types import StateAtTime
from coinbase_ml.common.utils.preprocessing_utils import pad_to_length
from coinbase_ml.fakebase.types import OrderSide
from .account_featurizer import AccountFeaturizer
from .info_dict_featurizer import InfoDictFeaturizer
from .order_book_featurizer import OrderBookFeaturizer
from .time_series_featurizer import TimeSeriesFeaturizer
from .types import Account, Exchange


class Featurizer(Generic[Exchange]):
    """
    Featurizer [summary]
    """

    def __init__(
        self,
        exchange: Exchange,
        reward_strategy: Type[BaseRewardStrategy],
        state_buffer_size: int,
    ) -> None:
        """
        __init__ [summary]

        Args:
            exchange (Exchange): [description]
            reward_strategy (Type[BaseRewardStrategy]): [description]
            state_buffer_size (int): [description]
        """
        self.state_buffer: Deque[StateAtTime] = Deque(maxlen=state_buffer_size)
        self._account_featurizer = AccountFeaturizer[Account](exchange.account)
        self._order_book_featurizer = OrderBookFeaturizer[Exchange](exchange)
        self._reward_strategy = reward_strategy()
        self._info_dict_featurizer = InfoDictFeaturizer(self._reward_strategy)
        self._time_series_featurizer = TimeSeriesFeaturizer[Exchange](exchange)

    def _get_order_book_feature(self) -> np.ndarray:
        """Summary

        Returns:
            np.ndarray: Description
        """
        _order_book: List[List[float]] = []
        for state in self.state_buffer:
            buy_order_book = (
                pad_to_length(state.buy_order_book[::-1], c.ORDER_BOOK_DEPTH)
                if len(state.buy_order_book) < c.ORDER_BOOK_DEPTH
                else state.buy_order_book[::-1][: c.ORDER_BOOK_DEPTH]
            )

            sell_order_book = (
                pad_to_length(state.sell_order_book, c.ORDER_BOOK_DEPTH)
                if len(state.sell_order_book) < c.ORDER_BOOK_DEPTH
                else state.sell_order_book[: c.ORDER_BOOK_DEPTH]
            )

            _order_book.append([])
            for (pa, va), (pb, vb) in zip(sell_order_book, buy_order_book):
                _order_book[-1] += [pa, va, pb, vb]

        return np.array(_order_book)

    def _get_account_funds_features(self) -> np.ndarray:
        """
        _get_account_funds_features [summary]

        Returns:
            np.ndarray: [description]
        """
        return self.state_buffer[-1].normalized_account_funds

    def _get_time_series_features(self) -> np.ndarray:
        """
        _get_time_series_features [summary]

        Returns:
            np.ndarray: [description]
        """
        return np.array(
            [state_at_time.time_series for state_at_time in self.state_buffer]
        ).astype(float)

    def calculate_reward(self) -> float:
        """
        calculate_reward [summary]

        Raises:
            StateBufferIsEmpty: [description]

        Returns:
            float: [description]
        """
        if not self.state_buffer:
            raise StateBufferIsEmpty

        return self._reward_strategy.calculate_reward(self.state_buffer)

    def get_info_dict(self) -> Dict[str, float]:
        """
        get_info_dict [summary]

        Raises:
            StateBufferIsEmpty: [description]

        Returns:
            Dict[str, float]: [description]
        """
        if not self.state_buffer:
            raise StateBufferIsEmpty

        return self._info_dict_featurizer.get_info_dict(self.state_buffer)

    def get_observation(self) -> Observation:
        """
        get_observation assembles feature arrays out of feature rows in buffers.
        Will return up to num_time_intervals_to_keep rows.

        Raises:
            StateBufferIsEmpty: [description]

        Returns:
            Observation: [description]
        """
        if not self.state_buffer:
            raise StateBufferIsEmpty

        return Observation(
            account_funds=self._get_account_funds_features(),
            order_book=self._get_order_book_feature(),
            time_series=self._get_time_series_features(),
        )

    def reset(self, exchange: Exchange, state_buffer: Deque[StateAtTime]) -> None:
        """
        reset clears Featurizer.state_buffer, point Featurizer at a new Exchange,
        and calls the reset method on reward_strategy.

        Args:
            exchange (Exchange): [description]
            state_buffer (Deque[StateAtTime]): [description]
        """
        # Don't reset self._info_dict_featurizer since it doesn't point to an
        # Exchange or Account object

        self._account_featurizer = AccountFeaturizer[Account](exchange.account)
        self._order_book_featurizer = OrderBookFeaturizer[Exchange](exchange)
        self._time_series_featurizer = TimeSeriesFeaturizer[Exchange](exchange)
        self.state_buffer = deepcopy(state_buffer)
        self._reward_strategy.reset()

    def update_state_buffer(self, action: Optional[ActionBase]) -> None:
        """
        update_state_buffer generates features from the current state of Account and Exchange
        and stores them in Featurizer.state_buffer.
        """
        account_funds = self._account_featurizer.get_funds_as_array()
        buy_order_book = self._order_book_featurizer.get_order_book_features(
            OrderSide.buy
        )
        normalized_account_funds = (
            self._account_featurizer.get_funds_as_normalized_array()
        )
        sell_order_book = self._order_book_featurizer.get_order_book_features(
            OrderSide.sell
        )
        time_series = self._time_series_featurizer.get_time_series_features()

        state = StateAtTime(
            account_funds=account_funds,
            action=action,
            buy_order_book=buy_order_book,
            normalized_account_funds=normalized_account_funds,
            sell_order_book=sell_order_book,
            time_series=time_series,
        )

        self.state_buffer.append(state)


class StateBufferIsEmpty(Exception):
    """
    StateBufferIsEmpty [summary]
    """

    def __init__(self) -> None:
        """
        StateBufferIsEmpty [summary]

        Args:
            Exception ([type]): [description]
        """
        super().__init__(
            "Featurizer.state_buffer is empty. "
            "Please call Featurizer.update_state_buffer to add an element to it."
        )
