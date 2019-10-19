"""
 [summary]
"""
from collections import deque as Deque

from coinbase_train import constants as c


class BaseRewardStrategy:
    """
    [summary]
    """

    @staticmethod
    def _calc_mid_price(state_buffer: Deque, time_index: int) -> float:
        """
        _calc_mid_price [summary]

        Args:
            state_buffer (Deque): [description]
            time_index (int): [description]

        Returns:
            float: [description]
        """
        best_ask_price = (
            c.NORMALIZERS["PRICE"] * state_buffer[time_index]["sell_order_book"][0][0]
        )
        best_bid_price = (
            c.NORMALIZERS["PRICE"] * state_buffer[time_index]["buy_order_book"][-1][0]
        )

        return (best_ask_price + best_bid_price) / 2

    @staticmethod
    def calc_portfolio_value_at_time_index(
        state_buffer: Deque, time_index: int
    ) -> float:
        """
        calc_portfolio_value_at_time_index [summary]

        Args:
            state_buffer (Deque): [description]
            time_index (int): [description]

        Returns:
            float: [description]
        """
        funds = state_buffer[time_index]["account_funds"]

        usd = funds[0, 0]
        btc = funds[0, 2]
        mid_price = BaseRewardStrategy._calc_mid_price(state_buffer, time_index)

        return mid_price * btc + usd

    @staticmethod
    def _calc_latest_return(state_buffer: Deque) -> float:
        """
        _calc_latest_return [summary]

        Args:
            state_buffer (Deque): [description]

        Returns:
            float: [description]
        """
        return BaseRewardStrategy.calc_portfolio_value_at_time_index(
            state_buffer, -1
        ) - BaseRewardStrategy.calc_portfolio_value_at_time_index(state_buffer, -2)

    def calculate_reward(self, state_buffer: Deque) -> float:
        """
        calculate_reward [summary]

        Args:
            state_buffer (Deque): [description]

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


class CalmarRewardStrategy(BaseRewardStrategy):
    """
    This strategy incentivizes profit and disincentivizes drawdown. Each step will return the ratio
    of the change in USD value of the portfolio divded by the maximum drawdown incurred during the
    episode.
    """

    def __init__(self) -> None:
        self._peak_portfolio_value = 0.0
        self._max_drawdown = 1e-10

    def calculate_reward(self, state_buffer: Deque) -> float:
        """
        calculate_reward [summary]

        Args:
            state_buffer (Deque): [description]

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


class ProfitRewardStrategy(BaseRewardStrategy):
    """
    This strategy purely incentivizes profit. Every step returns a reward equal to the change in
    the USD value of the portfolio.
    """

    def calculate_reward(self, state_buffer: Deque) -> float:
        """
        __call__ [summary]

        Args:
            state_buffer (Deque): [description]

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
