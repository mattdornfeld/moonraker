"""
 [summary]
"""
from typing import Dict, Type

from coinbase_ml.common.reward.base_reward_strategy import BaseRewardStrategy
from coinbase_ml.common.reward.calmar_reward_strategy import CalmarRewardStrategy
from coinbase_ml.common.reward.log_return_reward_strategy import LogReturnRewardStrategy
from coinbase_ml.common.reward.profit_reward_strategy import ProfitRewardStrategy

REWARD_STRATEGIES: Dict[str, Type[BaseRewardStrategy]] = {
    "CalmarRewardStrategy": CalmarRewardStrategy,
    "LogReturnRewardStrategy": LogReturnRewardStrategy,
    "ProfitRewardStrategy": ProfitRewardStrategy,
}
