"""
InfoDictFeaturizer
"""
from enum import Enum
from typing import Deque, Dict, Optional

import coinbase_ml.common.reward.base_reward_strategy as reward
from ..action import NoTransaction
from ..types import StateAtTime
from ...fakebase.types import OrderSide

StateBuffer = Deque[StateAtTime]


class Metrics(Enum):
    """
    Metrics is an enum of quantities that are returned by the InfoDictFeaturizer.
    Note that REWARD is not returned by the InfoDictFeaturizer but is included here
    because
    """

    NUM_BUY_ORDERS_PLACED = "num_buy_orders_placed"
    NUM_NO_TRANSACTIONS = "num_no_transactions"
    NUM_SELL_ORDERS_PLACED = "num_sell_orders_placed"
    PORTFOLIO_VALUE = "portolio_value"
    REWARD = "reward"
    ROI = "roi"


class MetricsDict(Dict[Metrics, float]):
    """
    MetricsDict [summary]
    """

    def keys_to_str(self) -> Dict[str, float]:
        """
        MetricsDict [summary]

        Returns:
            Dict[str, float]: [description]
        """
        return {k.value: v for k, v in self.items()}  # pylint: disable=no-member


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

    def get_info_dict(self, state_buffer: StateBuffer) -> MetricsDict:
        """
        get_info_dict [summary]

        Args:
            state_buffer (StateBuffer): [description]

        Returns:
            MetricsDict: [description]
        """
        portfolio_value = self._calc_portfolio_value(state_buffer)

        return MetricsDict(
            {
                Metrics.NUM_BUY_ORDERS_PLACED: self._calc_num_buy_orders_placed(
                    state_buffer
                ),
                Metrics.NUM_NO_TRANSACTIONS: self._calc_num_no_transactions(
                    state_buffer
                ),
                Metrics.NUM_SELL_ORDERS_PLACED: self._calc_num_sell_orders_placed(
                    state_buffer
                ),
                Metrics.PORTFOLIO_VALUE: portfolio_value,
                Metrics.ROI: (portfolio_value - self._initial_portfolio_value)
                / self._initial_portfolio_value,
            }
        )
