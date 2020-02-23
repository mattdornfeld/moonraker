"""
InfoDictFeaturizer
"""
from typing import Deque, Dict, Optional

from fakebase.types import OrderSide

import coinbase_ml.common.reward.base_reward_strategy as reward
from coinbase_ml.common.action import NoTransaction
from coinbase_ml.common.types import StateAtTime

StateBuffer = Deque[StateAtTime]


class InfoDictFeaturizer:
    """
    This class provides functionality used to form the info_dict, which is then
    aggregated by rllib.
    """

    def __init__(self, reward_strategy: reward.BaseRewardStrategy) -> None:
        """
        __init__ [summary]

        Args:
            reward_strategy (reward.BaseRewardStrategy): [description]
        """
        self._reward_strategy = reward_strategy
        self._initial_portfolio_value: Optional[float] = None

    @staticmethod
    def _calc_num_buy_orders_placed(state_buffer: StateBuffer) -> float:
        """
        _calc_num_buy_orders_placed [summary]

        Args:
            state_buffer (StateBuffer): [description]

        Returns:
            float: [description]
        """
        return float(state_buffer[-1].action.order_side == OrderSide.buy)

    @staticmethod
    def _calc_num_sell_orders_placed(state_buffer: StateBuffer) -> float:
        """
        _calc_num_sell_orders_placed [summary]

        Args:
            state_buffer (StateBuffer): [description]

        Returns:
            float: [description]
        """
        return float(state_buffer[-1].action.order_side == OrderSide.sell)

    @staticmethod
    def _calc_num_no_transactions(state_buffer: StateBuffer) -> float:
        """
        _calc_num_no_transactions [summary]

        Args:
            state_buffer (StateBuffer): [description]

        Returns:
            float: [description]
        """
        return float(isinstance(state_buffer[-1].action, NoTransaction))

    def _calc_portfolio_value(self, state_buffer: StateBuffer) -> float:
        """
        calculate_portfolio_value [summary]

        Args:
            state_buffer (StateBuffer): [description]

        Returns:
            float: [description]
        """
        portfolio_value = self._reward_strategy.calc_portfolio_value_at_time_index(
            state_buffer=state_buffer, time_index=-1
        )

        if not self._initial_portfolio_value:
            self._initial_portfolio_value = portfolio_value

        return portfolio_value

    def get_info_dict(self, state_buffer: StateBuffer) -> Dict[str, float]:
        """
        get_info_dict [summary]

        Args:
            state_buffer (StateBuffer): [description]

        Returns:
            Dict[str, float]: [description]
        """
        portfolio_value = self._calc_portfolio_value(state_buffer)

        return {
            "num_buy_orders_placed": self._calc_num_buy_orders_placed(state_buffer),
            "num_sell_orders_placed": self._calc_num_sell_orders_placed(state_buffer),
            "num_no_transactions": self._calc_num_no_transactions(state_buffer),
            "portfolio_value": portfolio_value,
            "roi": (portfolio_value - self._initial_portfolio_value)
            / self._initial_portfolio_value,
        }
