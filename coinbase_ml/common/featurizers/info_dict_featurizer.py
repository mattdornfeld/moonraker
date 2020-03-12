"""
InfoDictFeaturizer
"""
from datetime import datetime
from enum import Enum
from typing import Dict, Optional

import coinbase_ml.common.constants as cc
import coinbase_ml.common.reward.base_reward_strategy as reward
from coinbase_ml.common.utils.time_utils import TimeInterval
from ..action import NoTransaction
from ..types import StateBuffer
from ...fakebase.types import OrderSide


class Metrics(Enum):
    """
    Metrics is an enum of quantities that are returned by the InfoDictFeaturizer.
    Note that REWARD is not returned by the InfoDictFeaturizer but is included here
    because
    """

    BUY_FEES_PAID = "buy_fees_paid"
    BUY_VOLUME_TRADED = "buy_volume_traded"
    NUM_BUY_ORDERS_PLACED = "num_buy_orders_placed"
    NUM_NO_TRANSACTIONS = "num_no_transactions"
    NUM_SELL_ORDERS_PLACED = "num_sell_orders_placed"
    PORTFOLIO_VALUE = "portolio_value"
    REWARD = "reward"
    ROI = "roi"
    SELL_FEES_PAID = "sell_fees_paid"
    SELL_VOLUME_TRADED = "sell_volume_traded"


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
        self._fees_paid = {
            OrderSide.buy: cc.PRODUCT_ID.quote_volume_type.get_zero_volume(),
            OrderSide.sell: cc.PRODUCT_ID.quote_volume_type.get_zero_volume(),
        }
        self._num_orders_placed = {OrderSide.buy: 0.0, OrderSide.sell: 0.0, None: 0.0}
        self._volume_traded = {
            OrderSide.buy: cc.PRODUCT_ID.quote_volume_type.get_zero_volume(),
            OrderSide.sell: cc.PRODUCT_ID.quote_volume_type.get_zero_volume(),
        }
        self._reward_strategy = reward_strategy
        self._initial_portfolio_value: Optional[float] = None
        self._step_interval = TimeInterval(end_dt=datetime.min, start_dt=datetime.min)

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

    def _increment_fees_paid(
        self, order_side: OrderSide, state_buffer: StateBuffer
    ) -> None:
        """
        _increment_fees_paid [summary]

        Args:
            order_side (OrderSide): [description]
            state_buffer (StateBuffer): [description]
        """
        if self._step_interval == state_buffer[-1].time_interval:
            return

        self._fees_paid[order_side] = sum(
            [
                match.fee
                for match in state_buffer[-1].account_matches
                if match.account_order_side == order_side
            ],
            self._fees_paid[order_side],
        )

    def _increment_num_orders_placed(self, state_buffer: StateBuffer) -> None:
        """
        _increment_num_orders_placed [summary]

        Args:
            state_buffer (StateBuffer): [description]
        """
        if self._step_interval == state_buffer[-1].time_interval:
            return

        last_action = state_buffer[-1].action

        order_side = (
            last_action.order_side
            if not isinstance(last_action, NoTransaction)
            else None
        )

        self._num_orders_placed[order_side] += 1.0

    def _increment_volume_traded(
        self, order_side: OrderSide, state_buffer: StateBuffer
    ) -> None:
        """
        _increment_volume_traded [summary]

        Args:
            order_side (OrderSide): [description]
            state_buffer (StateBuffer): [description]
        """
        if self._step_interval == state_buffer[-1].time_interval:
            return

        self._volume_traded[order_side] = sum(
            [
                match.usd_volume
                for match in state_buffer[-1].account_matches
                if match.account_order_side == order_side
            ],
            self._volume_traded[order_side],
        )

    def get_info_dict(self, state_buffer: StateBuffer) -> MetricsDict:
        """
        get_info_dict [summary]

        Args:
            state_buffer (StateBuffer): [description]

        Returns:
            MetricsDict: [description]
        """

        portfolio_value = self._calc_portfolio_value(state_buffer)

        self._increment_num_orders_placed(state_buffer)

        for order_side in [OrderSide.buy, OrderSide.sell]:
            self._increment_fees_paid(order_side, state_buffer)
            self._increment_volume_traded(order_side, state_buffer)

        self._step_interval = state_buffer[-1].time_interval

        return MetricsDict(
            {
                Metrics.BUY_FEES_PAID: float(self._fees_paid[OrderSide.buy].amount),
                Metrics.BUY_VOLUME_TRADED: float(
                    self._volume_traded[OrderSide.buy].amount
                ),
                Metrics.NUM_BUY_ORDERS_PLACED: self._num_orders_placed[OrderSide.buy],
                Metrics.NUM_NO_TRANSACTIONS: self._num_orders_placed[None],
                Metrics.NUM_SELL_ORDERS_PLACED: self._num_orders_placed[OrderSide.sell],
                Metrics.PORTFOLIO_VALUE: portfolio_value,
                Metrics.ROI: (portfolio_value - self._initial_portfolio_value)
                / self._initial_portfolio_value,
                Metrics.SELL_FEES_PAID: float(self._fees_paid[OrderSide.sell].amount),
                Metrics.SELL_VOLUME_TRADED: float(
                    self._volume_traded[OrderSide.sell].amount
                ),
            }
        )
