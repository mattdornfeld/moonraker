"""
 [summary]
"""
from typing import Deque

from coinbase_ml.common.utils import StateAtTime
from coinbase_ml.common.reward.base_reward_strategy import BaseRewardStrategy


class ProfitRewardStrategy(BaseRewardStrategy):
    """
    This strategy purely incentivizes profit. Every step returns a reward equal to the change in
    the USD value of the portfolio.
    """

    def calculate_reward(self, state_buffer: Deque[StateAtTime]) -> float:
        """
        __call__ [summary]

        Args:
            state_buffer (Deque[StateAtTime]): [description]

        Returns:
            float: [description]
        """
        return self._calc_latest_return(state_buffer)

    def reset(self) -> None:
        """
        reset [summary]

        Returns:
            None: [description]
        """
