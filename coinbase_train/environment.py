"""Summary
"""
import logging
from queue import deque

import numpy as np

from fakebase.constants import PRECISION
from fakebase.mock_auth_client import MockAuthenticatedClient
from fakebase.utils import IllegalTransactionException
from lib.rl.core import Env

from coinbase_train import constants as c
from coinbase_train.utils import round_to_min_precision, EnvironmentFinishedException

LOGGER = logging.getLogger(__name__)

class MockEnvironment(Env):

    """Summary
    
    Attributes:
        auth_client (MockAuthenticatedClient): Description
        initial_btc (float): Description
        initial_usd (float): Description
    """
    def __init__(self, end_dt, initial_usd, initial_btc, num_time_steps, num_workers, 
                 start_dt, time_delta, verbose=False):
        """Summary
        
        Args:
            initial_usd (float): Description
            initial_btc (float): Description
            num_time_steps (int): Description
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
        current_usd = self._buffer[-1][5][0, 0]
        previous_usd = self._buffer[-2][5][0, 0]

        return max(0.0, current_usd - previous_usd)

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
        _state = self._process_buffer()
        
        state = [_state[0], #matches
                 _state[1], #orders
                 self._stack_order_books(buy_order_book=_state[2], sell_order_book=_state[3]),
                 _state[4], #account_orders
                 _state[5] #account_funds
                ]

        return state

    def _make_transactions(self, action):
        """
        action: np.ndarray
        
        Returns
        
        message: dict
        
        Args:
            action (np.ndarray): Description
        
        Returns:
            Dict[str, str]: Description
        """

        size, price, post_only, do_nothing, cancel_all_orders = action

        price = max(price, 0)
        price = round_to_min_precision(price, PRECISION[c.FIAT_CURRENCY])

        size = round_to_min_precision(size, PRECISION[c.ACCOUNT_PRODUCT])
        

        if bool(round(cancel_all_orders)):
            self.auth_client.cancel_all(product_id=c.PRODUCT_ID)

        if bool(round(do_nothing)):
            message = {'id': None, 'message': 'No purchase made.'}
        else:

            if size > 0.0:

                message = self.auth_client.place_limit_order(
                    product_id=c.PRODUCT_ID, 
                    price=price, 
                    side='buy', 
                    size=size, 
                    post_only=bool(round(post_only)))

            else:

                message = self.auth_client.place_limit_order(
                    product_id=c.PRODUCT_ID, 
                    price=price, 
                    side='sell', 
                    size=abs(size), 
                    post_only=bool(round(post_only)))

        return message

    def _process_buffer(self):
        """Summary
        
        Returns:
            List[np.ndarray]: Description
        """
        num_branches = len(self._buffer[0])

        state_by_branch = [[] for _ in range(num_branches)]

        for state_at_time in self._buffer:
            for i, branch_state_at_time in enumerate(state_at_time):
                state_by_branch[i].append(branch_state_at_time)

        return [self._stack_branch_states_over_time_steps(branch_state_over_time) 
                for branch_state_over_time in state_by_branch]

    @property
    def _results_queue_is_empty(self):
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
        """Summary
        
        Args:
            buy_order_book (np.ndarray): Description
            sell_order_book (np.ndarray): Description
        
        Returns:
            np.ndarray: Description
        """
        return np.concatenate([
            np.expand_dims(buy_order_book, axis=-1),
            np.expand_dims(sell_order_book, axis=-1)
            ], axis=-1)

    def _warmup(self):
        for _ in range(self._buffer.maxlen - 1):

            self._exchange_step()

            state = self.auth_client.exchange.get_exchange_state_as_array(
                c.PAD_ORDER_BOOK_TO_LENGTH)
            
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
            action (np.ndarray): size, price, post_only, do_nothing, cancel_all_orders
        
        Returns:
            Tuple[List[np.ndarray], float, bool, Dict]: Description
        
        Raises:
            EnvironmentFinishedException: Description
        """
        if self.episode_finished:
            raise EnvironmentFinishedException

        try:
            self._make_transactions(action)
        except IllegalTransactionException as exception:            
            if self.verbose:
                LOGGER.exception(exception)
            
            self._made_illegal_transaction = True #pylint: disable=W0201

        self._exchange_step()

        self._buffer.append(
            self.auth_client.exchange.get_exchange_state_as_array(c.PAD_ORDER_BOOK_TO_LENGTH))

        state = self._get_state_from_buffer()

        reward = self._calculate_reward()  

        return state, reward, self.episode_finished, {}
