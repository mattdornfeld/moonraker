"""Summary
"""
from collections import deque
from datetime import datetime, timedelta
from decimal import Decimal
import logging
from math import inf
from typing import Dict, List, Tuple, Optional

import numpy as np
from keras import backend as K
from rl.core import Env
import tensorflow as tf

from fakebase.mock_auth_client import MockAuthenticatedClient
from fakebase import utils as fakebase_utils

from coinbase_train import constants as c
from coinbase_train.copula import GaussianCopula
from coinbase_train.exchange import Exchange
from coinbase_train.utils import (clamp_to_range, convert_to_bool, pad_to_length,
                                  EnvironmentFinishedException)

LOGGER = logging.getLogger(__name__)

# These tensor operations need to be declared as constants so that
# they aren't redeclared everytime the Environment is reset
GAUSSIAN_COUPULA_MU = tf.placeholder(dtype=tf.float32, shape=(2,))
GAUSSIAN_COUPULA_SIGMA_CHOLESKY = tf.placeholder(dtype=tf.float32, shape=(2, 2))
GAUSSIAN_COUPULA_SAMPLE_OPERATION = GaussianCopula(
    mu=GAUSSIAN_COUPULA_MU,
    sigma_cholesky=GAUSSIAN_COUPULA_SIGMA_CHOLESKY).sample()

class MockEnvironment(Env): #pylint: disable=W0223

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
            start_dt (datetime): Description
            time_delta (timedelta): Description
            verbose (bool, optional): Description
            num_warmup_time_steps (Optional[int], optional): defaults to num_time_steps
        """
        self._buffer: deque = deque(maxlen=num_time_steps) #pylint: disable=C0301
        self._closing_price = 0.0
        self._made_illegal_transaction = False
        self._num_warmup_time_steps = num_warmup_time_steps
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

    def _calculate_reward(self) -> float:
        """Change in total value of walllet in USD

        Returns:
            float: reward
        """
        funds = self._buffer[-1]['account_funds']
        old_funds = self._buffer[-2]['account_funds']

        usd = funds[0, 0] + funds[0, 1]
        old_usd = old_funds[0, 0] + old_funds[0, 1]
        delta_usd = usd - old_usd

        old_closing_price = self._closing_price

        self._update_closing_price()

        btc = funds[0, 2] + funds[0, 3]
        old_btc = old_funds[0, 2] + old_funds[0, 3]
        delta_btc_value = self._closing_price * btc - old_closing_price * old_btc

        delta_value = delta_usd + delta_btc_value

        return (-c.ILLEGAL_TRANSACTION_PENALTY if
                self._made_illegal_transaction else
                delta_value)

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

    def _check_out_of_funds(self) -> bool:
        """Summary

        Returns:
            bool: Description
        """
        return (
            self.exchange.account.funds[c.PRODUCT_CURRENCY] +
            self.exchange.account.funds[c.QUOTE_CURRENCY]
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

    def _update_closing_price(self) -> None:
        """Summary
        """
        if self._buffer[-1]['closing_price'] is not None:
            self._closing_price = float(self._buffer[-1]['closing_price'])

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
                           available_btc: Decimal,
                           available_usd: Decimal,
                           order_side: str,
                           post_only: bool,
                           max_transactions: int,
                           transaction_percent_funds_mean: float,
                           transaction_price_mean: float,
                           transaction_price_sigma_cholesky_00: float,
                           transaction_price_sigma_cholesky_10: float,
                           transaction_price_sigma_cholesky_11: float) -> None:
        """
        Args:
            available_btc (Decimal): Description
            available_usd (Decimal): Description
            order_side (str): Description
            post_only (bool): Description
            max_transactions (int): Description
            transaction_percent_funds_mean (float): Description
            transaction_price_mean (float): Description
            transaction_price_sigma_cholesky_00 (float): Description
            transaction_price_sigma_cholesky_10 (float): Description
            transaction_price_sigma_cholesky_11 (float): Description
        """

        sess = K.get_session()

        copula_mu = [transaction_price_mean, transaction_percent_funds_mean] #pylint: disable=C0103
        copula_sigma_cholesky = [
            [transaction_price_sigma_cholesky_00, 0.0],
            [transaction_price_sigma_cholesky_10, transaction_price_sigma_cholesky_11]
        ]

        total_percent_funds = Decimal(0.0)
        num_transactions = 0
        while (num_transactions < max_transactions and
               total_percent_funds < c.MAX_PERCENT_OF_FUNDS_TRANSACTED_PER_STEP and
               not self._made_illegal_transaction):

            percent_price, _percent_funds = sess.run(
                fetches=GAUSSIAN_COUPULA_SAMPLE_OPERATION,
                feed_dict={GAUSSIAN_COUPULA_MU: copula_mu,
                           GAUSSIAN_COUPULA_SIGMA_CHOLESKY: copula_sigma_cholesky})

            _price = fakebase_utils.round_to_currency_precision(c.QUOTE_CURRENCY, percent_price * c.NORMALIZERS['PRICE']) #pylint: disable=C0301
            price = clamp_to_range(
                num=_price,
                smallest=fakebase_utils.get_currency_min_value(c.QUOTE_CURRENCY),
                largest=inf)
            percent_funds = Decimal(_percent_funds * c.MAX_PERCENT_OF_FUNDS_TRANSACTED_PER_STEP)

            total_percent_funds += percent_funds

            if order_side == 'buy':
                available_funds = available_usd
                __size = percent_funds * available_funds / (price + Decimal(1e-10))
            else:
                available_funds = available_btc
                __size = percent_funds * available_funds

            _size = fakebase_utils.round_to_currency_precision(c.PRODUCT_CURRENCY, __size)
            size = clamp_to_range(
                num=_size,
                smallest=fakebase_utils.get_currency_min_value(c.PRODUCT_CURRENCY),
                largest=inf)

            try:

                self.auth_client.place_limit_order(
                    product_id=c.PRODUCT_ID,
                    price=price,
                    side=order_side,
                    size=size,
                    post_only=post_only,
                    time_to_live=c.ORDER_TIME_TO_LIVE)

                num_transactions += 1

            except fakebase_utils.IllegalTransactionException as exception:

                if isinstance(exception, fakebase_utils.InsufficientFundsException):
                    self._made_illegal_transaction = True

                if self.verbose:
                    LOGGER.exception(exception)

        if self.verbose:
            _sigma_cholesky = np.array(copula_sigma_cholesky)
            sigma = _sigma_cholesky.dot(_sigma_cholesky.T)
            LOGGER.info( #pylint: disable=W1203
                f"{num_transactions} / {max_transactions} {'post_only' if post_only else ''} {order_side} orders have " #pylint: disable=C0301
                f'been placed using {total_percent_funds} of {available_funds} funds. The orders '
                f'were drawn from a 2D Gaussian distribution with price_mean = '
                f"{c.NORMALIZERS['PRICE'] * copula_mu[0]} and percent_funds_mean = "
                f'{c.MAX_PERCENT_OF_FUNDS_TRANSACTED_PER_STEP * copula_mu[1]} '
                f'and covariance matrix {sigma}.')

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

            self._warmup()
            self._exchange_checkpoint = self.exchange.create_checkpoint()

        else:
            self.exchange.stop_database_workers()
            self.exchange = self._exchange_checkpoint.restore()

        self.auth_client = MockAuthenticatedClient(self.exchange)
        self.auth_client.deposit(currency='USD', amount=self.initial_usd)
        self.auth_client.deposit(currency='BTC', amount=self.initial_btc)

        self._made_illegal_transaction = False

        state = self._get_state_from_buffer()

        self._update_closing_price()

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

        (transaction_buy,
         transaction_percent_funds_mean,
         transaction_post_only,
         transaction_price_mean,
         transaction_price_sigma_cholesky_00,
         transaction_price_sigma_cholesky_10,
         transaction_price_sigma_cholesky_11,
         transaction_sell) = action[:c.NUM_ACTIONS]

        max_transactions = np.argmax(action[c.NUM_ACTIONS:])

        if self.verbose:
            LOGGER.info(action)

        transaction_percent_funds_mean = clamp_to_range(transaction_percent_funds_mean, 0.0, 1.0)
        transaction_post_only = convert_to_bool(transaction_post_only)
        transaction_price_mean = clamp_to_range(transaction_price_mean, 0.0, inf)
        transaction_price_sigma_cholesky_00 = clamp_to_range(
            transaction_price_sigma_cholesky_00, 0.0, inf)
        transaction_price_sigma_cholesky_10 = transaction_price_sigma_cholesky_10
        transaction_price_sigma_cholesky_11 = clamp_to_range(
            transaction_price_sigma_cholesky_11, 0.0, inf)

        available_usd = self.exchange.account.get_available_funds('USD')
        available_btc = self.exchange.account.get_available_funds('BTC')

        if max_transactions > 0:

            order_side = 'buy' if (np.argmax([transaction_buy, transaction_sell]) == 0) else 'sell'

            self._make_transactions(available_btc,
                                    available_usd,
                                    order_side,
                                    transaction_post_only,
                                    max_transactions,
                                    transaction_percent_funds_mean,
                                    transaction_price_mean,
                                    transaction_price_sigma_cholesky_00,
                                    transaction_price_sigma_cholesky_10,
                                    transaction_price_sigma_cholesky_11)

        else:
            if self.verbose:
                LOGGER.info( #pylint: disable=W1203
                    f'Placing no orders. Available USD = {available_usd}. '
                    f'Available BTC = {available_btc}.')

        self._exchange_step()

        exchange_state = self.exchange.get_exchange_state_as_arrays()

        self._buffer.append(exchange_state)

        state = self._get_state_from_buffer()

        reward = self._calculate_reward()

        if self.verbose:
            LOGGER.info(f'Shape of input = {[s.shape for s in state]}') #pylint: disable=W1203
            LOGGER.info(f'reward = {reward}') #pylint: disable=W1203

        return state, reward, self.episode_finished, {}
