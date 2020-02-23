"""
 [summary]
"""
from typing import Deque

from coinbase_ml.common import constants as c
from coinbase_ml.common.types import StateAtTime

StateBuffer = Deque[StateAtTime]


class BaseRewardStrategy:
    """
    BaseRewardStrategy is an abstract class. Subclass to implement specific reward strategies.
    """

    @staticmethod
    def _calc_mid_price(state_buffer: StateBuffer, time_index: int) -> float:
        """
        _calc_mid_price [summary]

        Args:
            state_buffer (StateBuffer): [description]
            time_index (int): [description]

        Returns:
            float: [description]
        """
        best_ask_price = (
            c.PRICE_NORMALIZER * state_buffer[time_index].sell_order_book[0][0]
        )
        best_bid_price = (
            c.PRICE_NORMALIZER * state_buffer[time_index].buy_order_book[-1][0]
        )

        return (best_ask_price + best_bid_price) / 2

    @staticmethod
    def calc_portfolio_value_at_time_index(
        state_buffer: StateBuffer, time_index: int
    ) -> float:
        """
        calc_portfolio_value_at_time_index [summary]

        Args:
            state_buffer (StateBuffer): [description]
            time_index (int): [description]

        Returns:
            float: [description]
        """
        funds = state_buffer[time_index].account_funds

        quote = funds[0, 0]
        product = funds[0, 2]
        mid_price = BaseRewardStrategy._calc_mid_price(state_buffer, time_index)

        return mid_price * product + quote

    @staticmethod
    def _calc_latest_return(state_buffer: StateBuffer) -> float:
        """
        _calc_latest_return [summary]

        Args:
            state_buffer (StateBuffer): [description]

        Returns:
            float: [description]
        """
        return BaseRewardStrategy.calc_portfolio_value_at_time_index(
            state_buffer, -1
        ) - BaseRewardStrategy.calc_portfolio_value_at_time_index(state_buffer, -2)

    def calculate_reward(self, state_buffer: StateBuffer) -> float:
        """
        calculate_reward [summary]

        Args:
            state_buffer (StateBuffer): [description]

        Raises:
            NotImplementedError: [description]

        Returns:
            float: [description]
        """
        raise NotImplementedError

    def reset(self) -> None:
        """
        reset [summary]

        Raises:
            NotImplementedError: [description]

        Returns:
            None: [description]
        """
        raise NotImplementedError
