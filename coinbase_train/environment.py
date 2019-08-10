"""Summary
"""
from collections import deque
from datetime import datetime, timedelta
from decimal import Decimal
from funcy import compose, partial, rpartial
import logging
from math import inf
from typing import Dict, List, Tuple, Optional

import numpy as np
from rl.core import Env

from fakebase import utils as fakebase_utils
from fakebase.mock_auth_client import MockAuthenticatedClient

from coinbase_train import constants as c
from coinbase_train.exchange import Exchange
from coinbase_train.reward import BaseRewardStrategy
from coinbase_train.utils import (clamp_to_range, convert_to_bool, pad_to_length,
                                  EnvironmentFinishedException)

LOGGER = logging.getLogger(__name__)

class Environment(Env): #pylint: disable=W0223

    """Summary

    Attributes:
        auth_client (MockAuthenticatedClient): Description
        end_dt (datetime): Description
        exchange (Exchange): Description
        initial_btc (float): Description
        initial_usd (float): Description
        num_workers (int): Description
        start_dt (datetime): Description
        time_delta (timedelta): Description
        verbose (bool): Description
    """
    def __init__(self,
                 end_dt: datetime,
                 initial_usd: Decimal,
                 initial_btc: Decimal,
                 num_time_steps: int,
                 num_workers: int,
                 reward_strategy: BaseRewardStrategy,
                 start_dt: datetime,
                 time_delta: timedelta,
                 verbose: bool = False,
                 num_warmup_time_steps: Optional[int] = None):
        """Summary

        Args:
            end_dt (datetime): Description
            initial_usd (Decimal): Description
            initial_btc (Decimal): Description
            num_time_steps (int): Description
            num_workers (int): Description
            reward_strategy (BaseRewardStrategy): Description
            start_dt (datetime): Description
            time_delta (timedelta): Description
            verbose (bool, optional): Description
            num_warmup_time_steps (Optional[int], optional): defaults to num_time_steps
        """
        self._buffer: deque = deque(maxlen=num_time_steps) #pylint: disable=C0301
        self._closing_price = 0.0
        self._made_illegal_transaction = False
        self._num_warmup_time_steps = num_warmup_time_steps
        self._reward_strategy = reward_strategy
        self.auth_client: Optional[MockAuthenticatedClient] = None
        self.end_dt = end_dt
        self.exchange: Optional[Exchange] = None
        self.initial_btc = initial_btc
        self.initial_usd = initial_usd
        self.num_workers = num_workers
        self.start_dt = start_dt
        self.time_delta = time_delta
        self.verbose = verbose

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
            rpartial(clamp_to_range, 0.0, 1.0)
        )(normalized_transaction_price)

    @staticmethod
    def _calc_transaction_size(
            available_btc: Decimal,
            available_usd: Decimal,
            is_buy: bool,
            transaction_price: Decimal) -> Decimal:
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
        transaction_size = (available_usd * (1 - c.BUY_RESERVE_FRACTION) / transaction_price if
                            is_buy else
                            available_btc)

        return fakebase_utils.round_to_currency_precision(c.PRODUCT_CURRENCY, transaction_size)

    def _cancel_expired_orders(self) -> None:
        """
        _cancel_expired_orders [summary]

        Returns:
            None: [description]
        """
        for order in list(self.exchange.account.orders.values()):
            if order.order_status != 'open':
                continue

            deadline = (datetime.max if order.time_to_live == timedelta.max else
                        order.time + order.time_to_live)

            if self.exchange.interval_start_dt >= deadline:
                self.exchange.cancel_order(order.order_id)

    def _check_is_out_of_funds(self) -> bool:
        """Summary

        Returns:
            bool: Description
        """
        return (
            self.exchange.account.funds[c.PRODUCT_CURRENCY]['balance'] +
            self.exchange.account.funds[c.QUOTE_CURRENCY]['balance']
            ) <= 0.0

    def _exchange_step(self) -> None:
        """Summary
        """
        if self.verbose and self._results_queue_is_empty:
            LOGGER.info('Data queue is empty. Waiting for next entry.')

        self.exchange.step()

        if self.verbose:
            interval_end_dt = self.exchange.interval_end_dt
            interval_start_dt = self.exchange.interval_start_dt
            LOGGER.info(f'Exchange stepped to {interval_start_dt}-{interval_end_dt}.') #pylint: disable=W1203

    def _form_order_book_array(self, order_book_depth: int) -> np.ndarray:
        """Summary

        Args:
            order_book_depth (int): Description

        Returns:
            np.ndarray: Description
        """
        _order_book: List[List[float]] = []
        for state in self._buffer:
            buy_order_book = (pad_to_length(state['buy_order_book'], order_book_depth)
                              if len(state['buy_order_book']) < order_book_depth else
                              state['buy_order_book'])

            sell_order_book = (pad_to_length(state['sell_order_book'], order_book_depth)
                               if len(state['sell_order_book']) < order_book_depth else
                               state['sell_order_book'])

            _order_book.append([])
            for (ps, vs), (pb, vb) in zip(sell_order_book[:order_book_depth],  #pylint: disable=C0103
                                          buy_order_book[-order_book_depth:][::-1]):
                _order_book[-1] += [ps, vs, pb, vb]

        return np.array(_order_book)

    def _get_state_from_buffer(self) -> List[np.ndarray]:
        """Summary

        Returns:
            List[np.ndarray]: Description
        """
        account_funds = self._buffer[-1]['account_funds']

        order_book = self._form_order_book_array(c.ORDER_BOOK_DEPTH)

        time_series = np.array([np.hstack((state_at_time['cancellation_statistics'],
                                           state_at_time['order_statistics'],
                                           state_at_time['match_statistics']))
                                for state_at_time in self._buffer]).astype(float)

        return [account_funds, order_book, time_series]

    def _make_transactions(self,
                           order_side: str,
                           transaction_price: Decimal,
                           transaction_size: Decimal) -> None:
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
            self.auth_client.place_limit_order(
                product_id=c.PRODUCT_ID,
                price=transaction_price,
                side=order_side,
                size=transaction_size,
                post_only=False,
                time_to_live=c.ORDER_TIME_TO_LIVE)

        except fakebase_utils.IllegalTransactionException as exception:
            if self.verbose:
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
        while len(self._buffer) > 0:
            self._buffer.pop()

        for _ in range(self._num_warmup_time_steps):

            self._exchange_step()

            state = self.exchange.get_exchange_state_as_arrays()

            self._buffer.append(state)

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

    def reset(self) -> List[np.ndarray]:
        """Summary

        Returns:
            List[np.ndarray]: Description
        """
        if self.verbose:
            LOGGER.info('Resetting the environment.')

        if self.exchange is None:
            self.exchange = Exchange(end_dt=self.end_dt,
                                     num_workers=self.num_workers,
                                     start_dt=self.start_dt,
                                     time_delta=self.time_delta)

            self.auth_client = MockAuthenticatedClient(self.exchange)
            self.auth_client.deposit(currency=c.QUOTE_CURRENCY, amount=self.initial_usd)
            self.auth_client.deposit(currency=c.PRODUCT_CURRENCY, amount=self.initial_btc)

            self._warmup()
            self._exchange_checkpoint = self.exchange.create_checkpoint()

        else:
            self.exchange.stop_database_workers()
            self.exchange = self._exchange_checkpoint.restore()
            self.auth_client = MockAuthenticatedClient(self.exchange)

        self._made_illegal_transaction = False

        state = self._get_state_from_buffer()

        self._reward_strategy.reset()

        if self.verbose:
            LOGGER.info('Environment reset.')

        return state

    def step(self, action: np.ndarray) -> Tuple[List[np.ndarray], float, bool, Dict]:
        """Summary

        Args:
            action (np.ndarray): Description

        Returns:
            Tuple[List[np.ndarray], float, bool, Dict]: Description

        Raises:
            EnvironmentFinishedException: Description
        """
        if self.episode_finished:
            raise EnvironmentFinishedException

        self._cancel_expired_orders()

        transaction_buy, transaction_none, normalized_price, _ = action

        available_usd = self.exchange.account.get_available_funds('USD')
        available_btc = self.exchange.account.get_available_funds('BTC')

        if self.verbose:
            LOGGER.info(action)
            LOGGER.info(  # pylint: disable=W1203
                f'Available USD = {available_usd}. Available BTC = {available_btc}.')

        no_transaction = convert_to_bool(transaction_none)

        if not no_transaction:
            is_buy = convert_to_bool(transaction_buy)

            order_side = 'buy' if is_buy else 'sell'

            transaction_price = self._calc_transaction_price(normalized_price)

            transaction_size = self._calc_transaction_size(
                available_btc,
                available_usd,
                is_buy,
                transaction_price)

            self._make_transactions(order_side,
                                    transaction_price,
                                    transaction_size)

        self._exchange_step()

        exchange_state = self.exchange.get_exchange_state_as_arrays()

        self._buffer.append(exchange_state)

        state = self._get_state_from_buffer()

        reward = self._reward_strategy.calculate_reward(self._buffer)

        self._made_illegal_transaction = self._check_is_out_of_funds()

        if self.verbose:
            LOGGER.info(f'Shape of input = {[s.shape for s in state]}') #pylint: disable=W1203
            LOGGER.info(f'reward = {reward}') #pylint: disable=W1203

        return state, reward, self.episode_finished, {}
