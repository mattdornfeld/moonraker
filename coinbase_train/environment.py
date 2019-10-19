"""Summary
"""
import logging
from collections import deque as Deque
from copy import deepcopy
from datetime import datetime, timedelta
from decimal import Decimal
from math import inf
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
from funcy import compose, partial, rpartial
from gym import Env
from gym.spaces import Box

from fakebase import utils as fakebase_utils

from coinbase_train import constants as c
from coinbase_train.exchange import Exchange
from coinbase_train.observations import (
    Observation,
    ObservationSpace,
    ObservationSpaceShape,
)
from coinbase_train.reward import BaseRewardStrategy
from coinbase_train.utils import (
    EnvironmentConfigs,
    EnvironmentFinishedException,
    clamp_to_range,
    convert_to_bool,
    pad_to_length,
)

LOGGER = logging.getLogger(__name__)


class Environment(Env):  # pylint: disable=W0223
    """
    Environment [summary]
    """

    def __init__(self, config: Dict[str, Any]) -> None:
        _config = EnvironmentConfigs(**config)
        self._initial_portfolio_value = 0.0
        self._state_buffer: Deque = Deque(
            maxlen=_config.num_time_steps
        )  # pylint: disable=C0301
        self._closing_price = 0.0
        self._made_illegal_transaction = False
        self._warmed_up_buffer: Deque = Deque(
            maxlen=_config.num_time_steps
        )  # pylint: disable=C0301
        self.action_space = Box(low=0.0, high=1.0, shape=(c.ACTOR_OUTPUT_DIMENSION,))
        self.config = _config
        self.exchange: Optional[Exchange] = None
        self.observation_space = ObservationSpace(
            shape=ObservationSpaceShape(
                account_funds=(1, 4),
                order_book=(_config.num_time_steps, 4 * c.ORDER_BOOK_DEPTH),
                time_series=(_config.num_time_steps, c.NUM_CHANNELS_IN_TIME_SERIES),
            )
        )
        self.is_test_environment = _config.is_test_environment

        self.reset()

    @staticmethod
    def _calc_transaction_price(normalized_transaction_price: float) -> Decimal:
        """
        _calc_transaction_price [summary]

        Args:
            normalized_transaction_price (float): [description]

        Returns:
            Decimal: [description]
        """
        min_value = fakebase_utils.get_currency_min_value(c.QUOTE_CURRENCY)

        return compose(
            partial(fakebase_utils.round_to_currency_precision, c.QUOTE_CURRENCY),
            Decimal,
            str,
            rpartial(clamp_to_range, min_value, inf),
            lambda price: c.MAX_PRICE * price,
            rpartial(clamp_to_range, 0.0, 1.0),
        )(normalized_transaction_price)

    @staticmethod
    def _calc_transaction_size(
        available_btc: Decimal,
        available_usd: Decimal,
        is_buy: bool,
        transaction_price: Decimal,
    ) -> Decimal:
        """
        _calc_transaction_size [summary]

        Args:
            available_btc (Decimal): [description]
            available_usd (Decimal): [description]
            is_buy (bool): [description]
            transaction_price (Decimal): [description]

        Returns:
            Decimal: [description]
        """
        transaction_size = (
            available_usd * (1 - c.BUY_RESERVE_FRACTION) / transaction_price
            if is_buy
            else available_btc
        )

        return fakebase_utils.round_to_currency_precision(
            c.PRODUCT_CURRENCY, transaction_size
        )

    def _cancel_expired_orders(self) -> None:
        """
        _cancel_expired_orders [summary]

        Returns:
            None: [description]
        """
        for order in list(self.exchange.account.orders.values()):
            if order.order_status != "open":
                continue

            deadline = (
                datetime.max
                if order.time_to_live == timedelta.max
                else order.time + order.time_to_live
            )

            if self.exchange.interval_start_dt >= deadline:
                self.exchange.cancel_order(order.order_id)

    def _check_is_out_of_funds(self) -> bool:
        """Summary

        Returns:
            bool: Description
        """
        return (
            self.exchange.account.funds[c.PRODUCT_CURRENCY]["balance"]
            + self.exchange.account.funds[c.QUOTE_CURRENCY]["balance"]
        ) <= 0.0

    def _exchange_step(self) -> None:
        """Summary
        """
        if c.VERBOSE and self._results_queue_is_empty:
            LOGGER.info("Data queue is empty. Waiting for next entry.")

        self.exchange.step()

        if c.VERBOSE:
            interval_end_dt = self.exchange.interval_end_dt
            interval_start_dt = self.exchange.interval_start_dt
            LOGGER.info(
                "Exchange stepped to %s-%s.", interval_start_dt, interval_end_dt
            )

    def _form_order_book_array(self, order_book_depth: int) -> np.ndarray:
        """Summary

        Args:
            order_book_depth (int): Description

        Returns:
            np.ndarray: Description
        """
        _order_book: List[List[float]] = []
        for state in self._state_buffer:
            buy_order_book = (
                pad_to_length(state["buy_order_book"], order_book_depth)
                if len(state["buy_order_book"]) < order_book_depth
                else state["buy_order_book"]
            )

            sell_order_book = (
                pad_to_length(state["sell_order_book"], order_book_depth)
                if len(state["sell_order_book"]) < order_book_depth
                else state["sell_order_book"]
            )

            _order_book.append([])
            for (ps, vs), (pb, vb) in zip(
                sell_order_book[:order_book_depth],  # pylint: disable=C0103
                buy_order_book[-order_book_depth:][::-1],
            ):
                _order_book[-1] += [ps, vs, pb, vb]

        return np.array(_order_book)

    def _get_observation_from_buffer(self) -> Observation:
        """Summary

        Returns:
            Observation: Description
        """
        account_funds = self._state_buffer[-1]["normalized_account_funds"]

        order_book = self._form_order_book_array(c.ORDER_BOOK_DEPTH)

        time_series = np.array(
            [
                np.hstack(
                    (
                        state_at_time["cancellation_statistics"],
                        state_at_time["order_statistics"],
                        state_at_time["match_statistics"],
                    )
                )
                for state_at_time in self._state_buffer
            ]
        ).astype(float)

        return Observation(account_funds, order_book, time_series)

    def _make_transactions(
        self, order_side: str, transaction_price: Decimal, transaction_size: Decimal
    ) -> None:
        """
        _make_transactions [summary]

        Args:
            order_side (str): [description]
            transaction_price (Decimal): [description]
            transaction_size (Decimal): [description]

        Returns:
            None: [description]
        """
        try:
            self.exchange.place_limit_order(
                product_id=c.PRODUCT_ID,
                price=transaction_price,
                side=order_side,
                size=transaction_size,
                post_only=False,
                time_to_live=c.ORDER_TIME_TO_LIVE,
            )

        except fakebase_utils.IllegalTransactionException as exception:
            if c.VERBOSE:
                LOGGER.exception(exception)

    @property
    def _results_queue_is_empty(self) -> bool:
        """Is results queue empty

        Returns:
            bool: Description
        """
        return self.exchange.database_workers.results_queue.qsize() == 0

    def _warmup(self) -> None:
        """Summary
        """
        while self._state_buffer:
            self._state_buffer.pop()

        for _ in range(self.config.num_warmup_time_steps):

            self._exchange_step()

            state = self.exchange.get_exchange_state_as_arrays()

            self._state_buffer.append(state)

        self._warmed_up_buffer = deepcopy(self._state_buffer)

    def close(self) -> None:
        """Summary
        """

    @property
    def episode_finished(self) -> bool:
        """Summary

        Returns:
            bool: True if training episode is finished.
        """
        return self.exchange.finished or self._made_illegal_transaction

    def reset(self) -> Observation:
        """Summary

        Returns:
            Observation: Description
        """
        if c.VERBOSE:
            LOGGER.info("Resetting the environment.")

        if self.exchange is None:
            self.exchange = Exchange(
                end_dt=self.config.end_dt,
                num_workers=self.config.num_workers,
                start_dt=self.config.start_dt,
                time_delta=self.config.time_delta,
            )

            self.exchange.account.add_funds(
                currency=c.QUOTE_CURRENCY, amount=self.config.initial_usd
            )
            self.exchange.account.add_funds(
                currency=c.PRODUCT_CURRENCY, amount=self.config.initial_btc
            )

            self._warmup()
            self._exchange_checkpoint = self.exchange.create_checkpoint()

        else:
            self.exchange.stop_database_workers()
            self.exchange = self._exchange_checkpoint.restore()
            self._state_buffer = deepcopy(self._warmed_up_buffer)

        self._made_illegal_transaction = False

        observation = self._get_observation_from_buffer()

        self.config.reward_strategy.reset()

        if c.VERBOSE:
            LOGGER.info("Environment reset.")

        self._initial_portfolio_value = BaseRewardStrategy.calc_portfolio_value_at_time_index(
            state_buffer=self._state_buffer, time_index=-1
        )

        return observation

    def step(self, action: np.ndarray) -> Tuple[Observation, float, bool, Dict]:
        """Summary

        Args:
            action (np.ndarray): Description

        Returns:
            Tuple[Observation, float, bool, Dict]: Description

        Raises:
            EnvironmentFinishedException: Description
        """
        if self.episode_finished:
            raise EnvironmentFinishedException

        self._cancel_expired_orders()

        transaction_buy, transaction_none, normalized_price, _ = action

        available_usd = self.exchange.account.get_available_funds("USD")
        available_btc = self.exchange.account.get_available_funds("BTC")

        if c.VERBOSE:
            LOGGER.info(action)
            LOGGER.info(  # pylint: disable=W1203
                f"Available USD = {available_usd}. Available BTC = {available_btc}."
            )

        no_transaction = convert_to_bool(transaction_none)

        if not no_transaction:
            is_buy = convert_to_bool(transaction_buy)

            order_side = "buy" if is_buy else "sell"

            transaction_price = self._calc_transaction_price(normalized_price)

            transaction_size = self._calc_transaction_size(
                available_btc, available_usd, is_buy, transaction_price
            )

            self._make_transactions(order_side, transaction_price, transaction_size)

        self._exchange_step()

        exchange_state = self.exchange.get_exchange_state_as_arrays()

        self._state_buffer.append(exchange_state)

        observation = self._get_observation_from_buffer()

        reward = self.config.reward_strategy.calculate_reward(self._state_buffer)

        self._made_illegal_transaction = self._check_is_out_of_funds()

        if c.VERBOSE:
            LOGGER.info(f"reward = {reward}")  # pylint: disable=W1203

        portfolio_value = BaseRewardStrategy.calc_portfolio_value_at_time_index(
            state_buffer=self._state_buffer, time_index=-1
        )

        info_dict = {
            "portfolio_value": portfolio_value,
            "roi": (portfolio_value - self._initial_portfolio_value)
            / self._initial_portfolio_value,
        }

        return observation, reward, self.episode_finished, info_dict
