"""
 [summary]
"""
from typing import Deque

from coinbase_ml.common.utils import StateAtTime
from coinbase_ml.common.reward.base_reward_strategy import BaseRewardStrategy


class CalmarRewardStrategy(BaseRewardStrategy):
    """
    This strategy incentivizes profit and disincentivizes drawdown. Each step will return the ratio
    of the change in USD value of the portfolio divded by the maximum drawdown incurred during the
    episode.
    """

    def __init__(self) -> None:
        self._peak_portfolio_value = 0.0
        self._max_drawdown = 1e-10

    def calculate_reward(self, state_buffer: Deque[StateAtTime]) -> float:
        """
        calculate_reward [summary]

        Args:
            state_buffer (Deque[StateAtTime]): [description]

        Returns:
            float: [description]
        """
        latest_return = self._calc_latest_return(state_buffer)
        portfolio_value = self.calc_portfolio_value_at_time_index(state_buffer, -1)

        if portfolio_value - self._peak_portfolio_value > 0:
            self._peak_portfolio_value = portfolio_value
        elif portfolio_value - self._peak_portfolio_value < -self._max_drawdown:
            self._max_drawdown = abs(portfolio_value - self._peak_portfolio_value)

        return latest_return / self._max_drawdown

    def reset(self) -> None:
        self._peak_portfolio_value = 0.0
        self._max_drawdown = 1e-10
