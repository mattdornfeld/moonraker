"""Summary
"""
from datetime import datetime, timedelta
from decimal import Decimal
import logging
from math import inf
from queue import deque
from typing import Dict, List, Tuple

import numpy as np
from keras import backend as K
import tensorflow as tf

from fakebase.mock_auth_client import MockAuthenticatedClient
from fakebase import utils as fakebase_utils
from lib.rl.core import Env

from coinbase_train import constants as c
from coinbase_train.copula import GaussianCopula
from coinbase_train.exchange import Exchange
from coinbase_train.utils import (clamp_to_range, convert_to_bool, EnvironmentFinishedException)

LOGGER = logging.getLogger(__name__)

class MockEnvironment(Env): #pylint: disable=W0223

    """Summary
    
    Attributes:
        auth_client (MockAuthenticatedClient): Description
        end_dt (TYPE): Description
        gaussian_coupula_mu (TYPE): Description
        gaussian_coupula_sample_operation (TYPE): Description
        gaussian_coupula_sigma_cholesky (TYPE): Description
        initial_btc (float): Description
        initial_usd (float): Description
        num_workers (TYPE): Description
        start_dt (TYPE): Description
        time_delta (TYPE): Description
        verbose (TYPE): Description
    """
    def __init__(self, 
                 end_dt: datetime, 
                 initial_usd: Decimal, 
                 initial_btc: Decimal, 
                 num_time_steps: int, 
                 num_workers: int, 
                 start_dt: datetime, 
                 time_delta: timedelta, 
                 verbose: bool = False):
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
        """

        self._buffer = deque(maxlen=num_time_steps)
        self._closing_price = 0.0
        self._made_illegal_transaction = False
        self.auth_client = MockAuthenticatedClient()
        self.end_dt = end_dt
        self.initial_btc = initial_btc
        self.initial_usd = initial_usd
        self.num_workers = num_workers
        self.start_dt = start_dt
        self.time_delta = time_delta
        self.verbose = verbose

        self.gaussian_coupula_mu = tf.placeholder(dtype=tf.float32, shape=(2,))
        self.gaussian_coupula_sigma_cholesky = tf.placeholder(dtype=tf.float32, shape=(2, 2))
        self.gaussian_coupula_sample_operation = GaussianCopula(
            mu=self.gaussian_coupula_mu, 
            sigma_cholesky=self.gaussian_coupula_sigma_cholesky).sample()
        
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
                max(0.0, delta_value))

    def _exchange_step(self) -> None:
        """Summary
        """
        if self.verbose and self._results_queue_is_empty:
            LOGGER.info('Data queue is empty. Waiting for next entry.')
        
        self.auth_client.exchange.step()

        if self.verbose:
            interval_end_dt = self.auth_client.exchange.interval_end_dt
            interval_start_dt = self.auth_client.exchange.interval_start_dt
            LOGGER.info(f'Exchange stepped to {interval_start_dt}-{interval_end_dt}.') #pylint: disable=W1203

    def _update_closing_price(self):
        if self._buffer[-1]['closing_price'] is not None:
            self._closing_price = float(self._buffer[-1]['closing_price'])

    def _get_state_from_buffer(self) -> List[np.ndarray]:
        """Summary
        
        Returns:
            List[np.ndarray]: Description
        """
        account_funds = self._buffer[-1]['account_funds']

        order_book = self._stack_order_books(buy_order_book=self._buffer[-1]['buy_order_book'], 
                                             sell_order_book=self._buffer[-1]['sell_order_book'])

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
                           transaction_price_sigma_cholesky_11: float):
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
                fetches=self.gaussian_coupula_sample_operation, 
                feed_dict={self.gaussian_coupula_mu: copula_mu, 
                           self.gaussian_coupula_sigma_cholesky: copula_sigma_cholesky}) 

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
                    post_only=post_only)

                num_transactions += 1

            except fakebase_utils.IllegalTransactionException as exception:
                
                self._made_illegal_transaction = True #pylint: disable=W0201
                
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
    def _results_queue_is_empty(self):
        """Is results queue empty
        
        Returns:
            bool: Description
        """
        return self.auth_client.exchange.database_workers.results_queue.qsize() == 0

    @staticmethod
    def _stack_branch_states_over_time_steps(branch_state_over_time):
        """Summary
        
        Args:
            branch_state_over_time (List[np.ndarray]): Description
        
        Returns:
            np.ndarray: Description
        """
        max_length = max([len(branch_state_at_time) 
                          for branch_state_at_time in branch_state_over_time])

        _branch_state_over_time = []
        for branch_state_at_time in branch_state_over_time:
            
            _branch_state_at_time = np.pad(
                array=branch_state_at_time, 
                pad_width=((0, max_length - len(branch_state_at_time)), (0, 0)), 
                mode='constant', 
                constant_values=(0,))

            _branch_state_at_time = np.expand_dims(_branch_state_at_time, axis=0)

            _branch_state_over_time.append(_branch_state_at_time)

        return np.vstack(_branch_state_over_time)

    @staticmethod
    def _stack_order_books(buy_order_book, sell_order_book):
        """Stacks order books. Will pad smaller order book to have
        same size as larger one.
        
        Args:
            buy_order_book (np.ndarray): Description
            sell_order_book (np.ndarray): Description
        
        Returns:
            np.ndarray: Description
        """

        orders_tuple = (buy_order_book.shape[0], sell_order_book.shape[0])
        most_orders = max(orders_tuple)
        least_orders = min(orders_tuple)
        pad_width = ((0, most_orders - least_orders), (0, 0))

        if np.argmax(orders_tuple) == 0:
            sell_order_book = np.pad(
                array=sell_order_book, 
                pad_width=pad_width, 
                mode='constant', 
                constant_values=(0,))
        else:
            buy_order_book = np.pad(
                array=buy_order_book, 
                pad_width=pad_width, 
                mode='constant', 
                constant_values=(0,)) 

        return np.concatenate([
            np.expand_dims(buy_order_book, axis=-1),
            np.expand_dims(sell_order_book, axis=-1)], axis=-1)

    def _warmup(self):
        for _ in range(self._buffer.maxlen - 1):

            self._exchange_step()

            state = self.auth_client.exchange.get_exchange_state_as_arrays()
            
            self._buffer.append(state)

    def close(self):
        """Summary
        """

    @property
    def episode_finished(self):
        """Summary
        
        Returns:
            bool: True if training episode is finished.
        """
        return self.auth_client.exchange.finished or self._made_illegal_transaction

    def reset(self):
        """Summary
        """
        if self.verbose:
            LOGGER.info('Resetting the environment.')

        if self.auth_client.exchange is not None:
            self.auth_client.exchange.stop_database_workers()

        exchange = Exchange(end_dt=self.end_dt, 
                            num_workers=self.num_workers, 
                            start_dt=self.start_dt, 
                            time_delta=self.time_delta)

        del self.auth_client

        self.auth_client = MockAuthenticatedClient(exchange)

        self.auth_client.deposit(currency='USD', amount=self.initial_usd)
        self.auth_client.deposit(currency='BTC', amount=self.initial_btc)

        self._made_illegal_transaction = False

        self._warmup()

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

        available_usd = self.auth_client.exchange.account.get_available_funds('USD')
        available_btc = self.auth_client.exchange.account.get_available_funds('BTC')

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

        exchange_state = self.auth_client.exchange.get_exchange_state_as_arrays()

        self._buffer.append(exchange_state)

        state = self._get_state_from_buffer()

        reward = self._calculate_reward()  

        if self.verbose:
            LOGGER.info(f'Shape of input = {[s.shape for s in state]}') #pylint: disable=W1203
            LOGGER.info(f'reward = {reward}') #pylint: disable=W1203

        return state, reward, self.episode_finished, {}
