"""Summary
"""
import logging
from collections import deque as Deque
from copy import deepcopy
from typing import Dict, Optional, Tuple

import numpy as np
from gym import Env

from ray.rllib.env.env_context import EnvContext

from fakebase.exchange import Account, Exchange

from coinbase_ml.common import constants as cc
from coinbase_ml.common.action import ActionExecutor
from coinbase_ml.common.featurizers import Featurizer
from coinbase_ml.common.observations import (
    ActionSpace,
    Observation,
    ObservationSpace,
    ObservationSpaceShape,
)
from coinbase_ml.train import constants as c
from coinbase_ml.train.utils.config_utils import EnvironmentConfigs
from coinbase_ml.train.utils.exception_utils import EnvironmentFinishedException

LOGGER = logging.getLogger(__name__)


class Environment(Env):  # pylint: disable=W0223
    """
    Environment [summary]
    """

    def __init__(self, config: EnvContext) -> None:
        _config = EnvironmentConfigs(**config)
        self._closing_price = 0.0
        self._initial_portfolio_value = 0.0
        self._made_illegal_transaction = False
        self._warmed_up_buffer: Deque = Deque(maxlen=_config.num_time_steps)
        self.action_executor: Optional[ActionExecutor[Account]] = None
        self.action_space = ActionSpace()
        self.config = _config
        self.exchange: Optional[Exchange] = None
        self.featurizer: Optional[Featurizer] = None
        self.observation_space = ObservationSpace(
            shape=ObservationSpaceShape(
                account_funds=(1, 4),
                order_book=(_config.num_time_steps, 4 * cc.ORDER_BOOK_DEPTH),
                time_series=(_config.num_time_steps, cc.NUM_CHANNELS_IN_TIME_SERIES),
            )
        )
        self.is_test_environment = _config.is_test_environment

        # Ray does something weird where it creates a local copy of the environment with
        # config.worker_index=0 but it doesn't seem to actually use it. This block of code
        # takes care of that case.
        if config.worker_index == 0 and not self.is_test_environment:
            self._start_dt = None
            self._end_dt = None
        else:
            worker_index = (
                config.worker_index
                if self.is_test_environment
                else config.worker_index - 1
            )
            environment_time_interval = _config.environment_time_intervals[worker_index]
            self._start_dt = environment_time_interval.start_dt
            self._end_dt = environment_time_interval.end_dt
            self.reset()

    def _check_is_out_of_funds(self) -> bool:
        """Summary

        Returns:
            bool: Description
        """
        return (
            self.exchange.account.funds[cc.PRODUCT_CURRENCY].balance
            + self.exchange.account.funds[cc.QUOTE_CURRENCY].balance
        ) <= 0.0

    def _exchange_step(self) -> None:
        """Summary
        """
        if c.VERBOSE and self._results_queue_is_empty:
            LOGGER.info("Data queue is empty. Waiting for next entry.")

        self.exchange.step()
        self.featurizer.update_state_buffer()

        if c.VERBOSE:
            interval_end_dt = self.exchange.interval_end_dt
            interval_start_dt = self.exchange.interval_start_dt
            LOGGER.info(
                "Exchange stepped to %s-%s.", interval_start_dt, interval_end_dt
            )

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
        while self.featurizer.state_buffer:
            self.featurizer.state_buffer.pop()

        for _ in range(self.config.num_warmup_time_steps):
            self._exchange_step()

        self._warmed_up_buffer = deepcopy(self.featurizer.state_buffer)

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
                end_dt=self._end_dt,
                start_dt=self._start_dt,
                time_delta=self.config.time_delta,
            )

            self.featurizer = Featurizer(
                exchange=self.exchange,
                reward_strategy=self.config.reward_strategy,
                state_buffer_size=self.config.num_time_steps,
            )

            self.action_executor = ActionExecutor[Account](self.exchange.account)

            self.exchange.account.add_funds(
                currency=cc.QUOTE_CURRENCY, amount=self.config.initial_usd
            )
            self.exchange.account.add_funds(
                currency=cc.PRODUCT_CURRENCY, amount=self.config.initial_btc
            )

            self._warmup()
            self._exchange_checkpoint = self.exchange.create_checkpoint()

        else:
            self.exchange.stop_database_workers()
            self.exchange = self._exchange_checkpoint.restore()
            self.action_executor.account = self.exchange.account

        self._made_illegal_transaction = False

        observation = self.featurizer.get_observation()

        if c.VERBOSE:
            LOGGER.info("Environment reset.")

        self._initial_portfolio_value = self.featurizer.calculate_portfolio_value()

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

        self.action_executor.cancel_expired_orders(self.exchange.interval_start_dt)

        _action = self.action_executor.translate_model_prediction_to_action(action)
        self.action_executor.act(_action)

        if c.VERBOSE:
            LOGGER.info(_action)

        self._exchange_step()

        self.featurizer.update_state_buffer()
        observation = self.featurizer.get_observation()
        reward = self.featurizer.calculate_reward()

        self._made_illegal_transaction = self._check_is_out_of_funds()

        if c.VERBOSE:
            LOGGER.info("reward = %s", reward)

        portfolio_value = self.featurizer.calculate_portfolio_value()

        info_dict = {
            "portfolio_value": portfolio_value,
            "roi": (portfolio_value - self._initial_portfolio_value)
            / self._initial_portfolio_value,
        }

        return observation, reward, self.episode_finished, info_dict
