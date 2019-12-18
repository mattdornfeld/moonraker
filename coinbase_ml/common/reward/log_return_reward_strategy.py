"""
 [summary]
"""
from typing import Deque

from coinbase_ml.common.utils import StateAtTime, log_epsilon
from coinbase_ml.common.reward.base_reward_strategy import BaseRewardStrategy


class LogReturnRewardStrategy(BaseRewardStrategy):
    """
    LogReturnRewardStrategy optimizes log(p_t / p_{t-1}) where p_t
    is the portfolio value at time t
    """

    def calculate_reward(self, state_buffer: Deque[StateAtTime]) -> float:
        """
        __call__ [summary]

        Args:
            state_buffer (Deque[StateAtTime]): [description]

        Returns:
            float: [description]
        """

        return log_epsilon(
            self.calc_portfolio_value_at_time_index(state_buffer, -1)
            / self.calc_portfolio_value_at_time_index(state_buffer, -2)
        )

    def reset(self) -> None:
        """
        reset [summary]

        Returns:
            None: [description]
        """
