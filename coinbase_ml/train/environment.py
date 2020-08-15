"""Summary
"""
import logging
from collections import deque
from copy import deepcopy
from typing import Deque, Dict, Optional, Tuple

import numpy as np
from gym import Env

from ray.rllib.env.env_context import EnvContext

from coinbase_ml.common import constants as cc
from coinbase_ml.common.action import ActionBase
from coinbase_ml.common.actionizers import Actionizer
from coinbase_ml.common.featurizers import Featurizer
from coinbase_ml.common.observations import (
    ActionSpace,
    Observation,
    ObservationSpace,
    ObservationSpaceShape,
)
from coinbase_ml.common.types import StateAtTime
from coinbase_ml.fakebase.account import Account
from coinbase_ml.fakebase.exchange import Exchange
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
        self._made_illegal_transaction = False
        self._warmed_up = False
        self._warmed_up_buffer: Deque[StateAtTime] = deque(
            maxlen=_config.num_time_steps
        )
        self.action_space = ActionSpace()
        self.config = _config
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
            self.exchange = None
        else:
            worker_index = (
                config.worker_index
                if self.is_test_environment
                else config.worker_index - 1
            )
            environment_time_interval = _config.environment_time_intervals[worker_index]
            self._start_dt = environment_time_interval.start_dt
            self._end_dt = environment_time_interval.end_dt
            self.exchange = Exchange(
                end_dt=self._end_dt,
                product_id=cc.PRODUCT_ID,
                start_dt=self._start_dt,
                time_delta=self.config.time_delta,
            )
            self.reset()

    def _check_is_out_of_funds(self) -> bool:
        """Summary

        Returns:
            bool: Description
        """
        out_of_product = (
            self.exchange.account.funds[cc.PRODUCT_CURRENCY].balance
            <= cc.PRODUCT_ID.product_volume_type.get_zero_volume()
        )
        out_of_quote = (
            self.exchange.account.funds[cc.QUOTE_CURRENCY].balance
            <= cc.PRODUCT_ID.quote_volume_type.get_zero_volume()
        )

        return out_of_product and out_of_quote

    def _exchange_step(self, action: ActionBase) -> None:
        """
        _exchange_step [summary]

        Args:
            action (Optional[ActionBase]): [description]
        """
        if c.VERBOSE and self._results_queue_is_empty:
            LOGGER.info("Data queue is empty. Waiting for next entry.")

        self.exchange.step()
        self.featurizer.update_state_buffer(action)

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

        actionizer = Actionizer[Account](self.exchange.account)
        no_transaction = actionizer.get_action()
        for _ in range(self.config.num_warmup_time_steps):
            self._exchange_step(no_transaction)

        # Call self.featurizer.get_info_dict to inititialize values
        # in InfoDictFeaturizer
        self.featurizer.get_info_dict()

        self._warmed_up_buffer = deepcopy(self.featurizer.state_buffer)
        self._warmed_up = True

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

        if not self._warmed_up:
            self.featurizer = Featurizer(
                exchange=self.exchange,
                reward_strategy=self.config.reward_strategy,
                state_buffer_size=self.config.num_time_steps,
            )

            self.exchange.start(
                initial_product_funds=self.config.initial_btc,
                initial_quote_funds=self.config.initial_usd,
                num_warmup_time_steps=0,
                snapshot_buffer_size=3,
            )

            self._warmup()
            self.exchange.checkpoint()
        else:
            self.exchange.reset()

            self.featurizer.reset(
                exchange=self.exchange, state_buffer=self._warmed_up_buffer
            )

        self._made_illegal_transaction = False

        observation = self.featurizer.get_observation()

        if c.VERBOSE:
            LOGGER.info("Environment reset.")

        return observation

    def step(
        self, action: np.ndarray
    ) -> Tuple[Observation, float, bool, Dict[str, float]]:
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

        actionizer = Actionizer[Account](self.exchange.account, action)
        action = actionizer.get_action()
        action.cancel_expired_orders(self.exchange.interval_start_dt)
        action.execute()

        self._exchange_step(action)

        observation = self.featurizer.get_observation()
        reward = self.featurizer.calculate_reward()
        self._made_illegal_transaction = self._check_is_out_of_funds()

        if c.VERBOSE:
            LOGGER.info("reward = %s", reward)

        info_dict = self.featurizer.get_info_dict()

        return observation, reward, self.episode_finished, info_dict.keys_to_str()
