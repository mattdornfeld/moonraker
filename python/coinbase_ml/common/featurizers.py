from enum import Enum
from typing import Dict, Type, TypeVar

import numpy as np

from coinbase_ml.common.action import ActionBase
from coinbase_ml.common.observations import Observation
from coinbase_ml.common.reward import BaseRewardStrategy
from coinbase_ml.fakebase.base_classes import ExchangeBase


Exchange = TypeVar("Exchange", bound=ExchangeBase)


class Featurizer:
    def __init__(
        self,
        exchange: Exchange,
        reward_strategy: Type[BaseRewardStrategy],
        state_buffer_size: int,
    ):
        pass

    @staticmethod
    def calculate_reward() -> float:
        return 1.0

    @staticmethod
    def get_info_dict() -> dict:
        return dict()

    @staticmethod
    def get_observation() -> Observation:
        return Observation(np.zeros(1), np.zeros(1), np.zeros(1))

    @staticmethod
    def update_state_buffer(action: ActionBase) -> None:
        pass


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
