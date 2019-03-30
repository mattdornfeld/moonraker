"""Summary
"""
import logging
from math import inf
from queue import deque

import numpy as np
from keras import backend as K
import tensorflow as tf

from fakebase.constants import PRECISION
from fakebase.mock_auth_client import MockAuthenticatedClient
from fakebase.utils import IllegalTransactionException
from lib.rl.core import Env

from coinbase_train import constants as c
from coinbase_train.copula import GaussianCopula
from coinbase_train.utils import (clamp_to_range, convert_to_bool, round_to_min_precision, 
                                  EnvironmentFinishedException)

GAUSSIAN_COUPULA_MU = tf.placeholder(dtype=tf.float32, shape=(2,))
GAUSSIAN_COUPULA_SIGMA_CHOLESKY = tf.placeholder(dtype=tf.float32, shape=(2, 2))
GAUSSIAN_COUPULA_SAMPLE_OPERATION = GaussianCopula(
    mu=GAUSSIAN_COUPULA_MU, 
    sigma_cholesky=GAUSSIAN_COUPULA_SIGMA_CHOLESKY).sample()
LOGGER = logging.getLogger(__name__)

class MockEnvironment(Env):

    """Summary
    
    Attributes:
        auth_client (MockAuthenticatedClient): Description
        end_dt (TYPE): Description
        initial_btc (float): Description
        initial_usd (float): Description
        num_workers (TYPE): Description
        start_dt (TYPE): Description
        time_delta (TYPE): Description
        verbose (TYPE): Description
    """
    def __init__(self, end_dt, initial_usd, initial_btc, num_time_steps, num_workers, 
                 start_dt, time_delta, verbose=False):
        """Summary
        
        Args:
            end_dt (TYPE): Description
            initial_usd (float): Description
            initial_btc (float): Description
            num_time_steps (int): Description
            num_workers (TYPE): Description
            start_dt (TYPE): Description
            time_delta (TYPE): Description
            verbose (bool, optional): Description
        """

        self._buffer = deque(maxlen=num_time_steps)
        self.auth_client = MockAuthenticatedClient()
        self.end_dt = end_dt
        self.initial_btc = initial_btc
        self.initial_usd = initial_usd
        self.num_workers = num_workers
        self.start_dt = start_dt
        self.time_delta = time_delta
        self.verbose = verbose
        
        self.reset()

    def _calculate_reward(self):
        """Calculates the amount of USD gained this time step.
        If gain is negative will return 0.0, since we do not want 
        to penalize buying product. We just want to reward selling it.
        
        Returns:
            float: reward
        """
        current_usd = self._buffer[-1]['account_funds'][0, 0]
        previous_usd = self._buffer[-2]['account_funds'][0, 0]

        return max(0.0, current_usd - previous_usd)

    def _cancel_orders_in_range(self, cancel_side, cancel_max_price, cancel_min_price):
        """Summary
        
        Args:
            cancel_max_price (float): Description
            cancel_min_price (float): Description
            cancel_side (str): Description
        """

        canceled_orders = self.auth_client.cancel_all_orders_in_price_range(
            min_price=cancel_min_price, 
            max_price=cancel_max_price, 
            order_side=cancel_side)

        if self.verbose:
            LOGGER.info(#pylint: disable=W1203
                f'Canceled {len(canceled_orders)} {cancel_side} orders in range '
                f'{cancel_min_price} - {cancel_max_price}.') 

    def _exchange_step(self):
        """Summary
        """
        if self.verbose and self._results_queue_is_empty:
            LOGGER.info('Data queue is empty. Waiting for next entry.')
        
        self.auth_client.exchange.step()

        if self.verbose:
            interval_end_dt = self.auth_client.exchange.interval_end_dt
            interval_start_dt = self.auth_client.exchange.interval_start_dt
            LOGGER.info(f'Exchange stepped to {interval_start_dt}-{interval_end_dt}.') #pylint: disable=W1203

    def _get_state_from_buffer(self):
        """Summary
        
        Returns:
            List[np.ndarray]: Description
        """
        num_branches = len(self._buffer[0])

        state_by_branch = [[] for _ in range(num_branches)]

        for state_at_time in self._buffer:
            for i, branch_state_at_time in enumerate(state_at_time.values()):
                state_by_branch[i].append(branch_state_at_time)

        matches = self._stack_branch_states_over_time_steps(state_by_branch[0])

        # orders = self._stack_branch_states_over_time_steps(state_by_branch[1])

        buy_order_book = self._stack_branch_states_over_time_steps(state_by_branch[2])
        sell_order_book = self._stack_branch_states_over_time_steps(state_by_branch[3])
        order_book = self._stack_order_books(buy_order_book, sell_order_book)

        account_orders = state_by_branch[4][-1]
        
        account_funds = state_by_branch[5][-1]

        return [matches, order_book, account_orders, account_funds]

    def _make_transactions(self, 
                           available_btc, 
                           available_usd, 
                           order_side, 
                           post_only, 
                           max_transactions,
                           transaction_percent_funds_mean, 
                           transaction_price_mean,
                           transaction_price_sigma_cholesky_00,
                           transaction_price_sigma_cholesky_10,
                           transaction_price_sigma_cholesky_11):
        """
        Args:
            available_btc (float): Description
            available_usd (float): Description
            order_side (str): Description
            post_only (bool): Description
            max_transactions (int): Description
            transaction_percent_funds_mean (float): Description
            transaction_price_mean (float): Description
            transaction_price_sigma_cholesky_00 (float): Description
            transaction_price_sigma_cholesky_10 (float): Description
            transaction_price_sigma_cholesky_11 (float): Description
        
        Returns:
            Dict[str, str]: Description
        
        Deleted Parameters:
            buy_sell (bool): Description
            percent_funds (float): Description
            price (float): Description
        """

        sess = K.get_session()

        mu = [transaction_price_mean, transaction_percent_funds_mean] #pylint: disable=C0103
        sigma_cholesky = [
            [transaction_price_sigma_cholesky_00, 0.0], 
            [transaction_price_sigma_cholesky_10, transaction_price_sigma_cholesky_11]
        ]
        
        total_percent_funds = 0.0
        num_transactions = 0 
        while (num_transactions < max_transactions and 
               total_percent_funds < c.MAX_PERCENT_OF_FUNDS_TRANSACTED_PER_STEP):
            
            percent_price, _percent_funds = sess.run(
                fetches=GAUSSIAN_COUPULA_SAMPLE_OPERATION, 
                feed_dict={GAUSSIAN_COUPULA_MU: mu, GAUSSIAN_COUPULA_SIGMA_CHOLESKY: sigma_cholesky}) #pylint: disable=C0301

            price = percent_price * c.MAX_PRICE
            percent_funds = _percent_funds * c.MAX_PERCENT_OF_FUNDS_TRANSACTED_PER_STEP

            price = round_to_min_precision(price, PRECISION[c.FIAT_CURRENCY])
            
            total_percent_funds += percent_funds
            num_transactions += 1

            if order_side == 'buy':

                available_funds = available_usd
                _size = percent_funds * available_funds / price
                size = round_to_min_precision(_size, PRECISION[c.ACCOUNT_PRODUCT])

            else:

                available_funds = available_btc
                _size = percent_funds * available_funds
                size = round_to_min_precision(_size, PRECISION[c.ACCOUNT_PRODUCT])

            message = self.auth_client.place_limit_order(
                product_id=c.PRODUCT_ID, 
                price=price, 
                side=order_side, 
                size=size, 
                post_only=post_only)        

        if self.verbose:
            _sigma_cholesky = np.array(sigma_cholesky)
            sigma = _sigma_cholesky.dot(_sigma_cholesky.T)
            LOGGER.info( #pylint: disable=W1203
                f"{num_transactions} {'post_only' if post_only else ''} {order_side} orders have been placed "
                f'using {total_percent_funds} of {available_funds} funds. The orders were ' 
                f'drawn from a 2D Gaussian distribution with price_mean = '
                f'{c.MAX_PRICE * mu[0]} and percent_funds_mean = '
                f'{c.MAX_PERCENT_OF_FUNDS_TRANSACTED_PER_STEP * mu[1]} '
                f'and covariance matrix {sigma}.')

        return message

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

        orders_tuple = (buy_order_book.shape[1], sell_order_book.shape[1])
        most_orders = max(orders_tuple)
        least_orders = min(orders_tuple)
        pad_width = ((0, 0), (0, most_orders - least_orders), (0, 0))

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

            state = self.auth_client.exchange.get_exchange_state_as_array()
            
            self._buffer.append(state)

    def close(self):
        """Summary
        """

    def configure(self, *args, **kwargs):
        """Summary
        """

    @property
    def episode_finished(self):
        """Summary
        
        Returns:
            bool: True if training episode is finished.
        """
        return self.auth_client.exchange.finished or self._made_illegal_transaction

    def render(self, mode='human', close=False):
        """Summary
        """

    def reset(self):
        """Summary
        """
        if self.verbose:
            LOGGER.info('Resetting the environment.')

        self.auth_client.init_exchange(
            end_dt=self.end_dt, 
            num_workers=self.num_workers, 
            start_dt=self.start_dt, 
            time_delta=self.time_delta)

        self.auth_client.deposit(currency='USD', amount=self.initial_usd)
        self.auth_client.deposit(currency='BTC', amount=self.initial_btc)

        self._made_illegal_transaction = False

        self._warmup()

        state = self._get_state_from_buffer()

        if self.verbose:
            LOGGER.info('Environment reset.')

        return state

    def seed(self, seed=None):
        """Summary
        """

    def step(self, action):
        """Summary
        
        Args:
            action (np.ndarray):
                percent_funds, price, buy_sell, post_only, should_buy, 
                should_cancel, cancel_buy_sell, cancel_min_price, cancel_max_price
        
        Returns:
            Tuple[List[np.ndarray], float, bool, Dict]: Description
        
        Raises:
            EnvironmentFinishedException: Description
        """
        if self.episode_finished:
            raise EnvironmentFinishedException

        (cancel_buy,
         cancel_max_price,
         cancel_min_price,
         cancel_none,
         cancel_sell,
         transaction_buy,
         transaction_none,
         max_transactions,
         transaction_percent_funds_mean,
         transaction_post_only,
         transaction_price_mean,
         transaction_price_sigma_cholesky_00,
         transaction_price_sigma_cholesky_10,
         transaction_price_sigma_cholesky_11,
         transaction_sell) = action

        if self.verbose:
            LOGGER.info(action)

        cancel_max_price = clamp_to_range(cancel_max_price, 0.0, inf)
        cancel_min_price = clamp_to_range(cancel_min_price, 0.0, inf)
        max_transactions = int(round(clamp_to_range(max_transactions, 0.0, inf)))
        transaction_percent_funds_mean = clamp_to_range(transaction_percent_funds_mean, 0.0, 1.0)
        transaction_post_only = convert_to_bool(transaction_post_only)
        transaction_price_mean = clamp_to_range(transaction_price_mean, 0.0, inf)
        transaction_price_sigma_cholesky_00 = clamp_to_range(
            transaction_price_sigma_cholesky_00, 0.0, inf)
        transaction_price_sigma_cholesky_10 = transaction_price_sigma_cholesky_10
        transaction_price_sigma_cholesky_11 = clamp_to_range(
            transaction_price_sigma_cholesky_11, 0.0, inf)

        should_cancel = np.argmax([cancel_buy, cancel_sell, cancel_none]) != 2
        if should_cancel and cancel_min_price < cancel_max_price:
            
            cancel_side = 'buy' if (np.argmax([cancel_buy, cancel_sell]) == 0) else 'sell'
            self._cancel_orders_in_range(cancel_side, cancel_max_price, cancel_min_price)

        else:
            if self.verbose:
                LOGGER.info('Canceling no orders.')

        should_buy = np.argmax([transaction_buy, transaction_sell, transaction_none]) != 2
        available_usd = self.auth_client.exchange.account.get_available_funds('USD')
        available_btc = self.auth_client.exchange.account.get_available_funds('BTC')

        if should_buy and max_transactions > 0:

            order_side = 'buy' if (np.argmax([transaction_buy, transaction_sell]) == 0) else 'sell'

            try:
                
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

            except IllegalTransactionException as exception:
                self._made_illegal_transaction = True #pylint: disable=W0201
                if self.verbose:
                    LOGGER.exception(exception)

        else:
            if self.verbose:
                LOGGER.info( #pylint: disable=W1203
                    f'Placing no orders. Available USD = {available_usd}. '
                    f'Available BTC = {available_btc}.')          

        self._exchange_step()

        exchange_state = self.auth_client.exchange.get_exchange_state_as_array()

        self._buffer.append(exchange_state)

        state = self._get_state_from_buffer()

        reward = self._calculate_reward()  

        if self.verbose:
            LOGGER.info('Shape of (matches, order_book, account_orders, account_funds) = {}'. #pylint: disable=W1202
                        format([s.shape for s in state]))
            LOGGER.info(f'reward = {reward}') #pylint: disable=W1203

        return state, reward, self.episode_finished, {}
