"""Summary
"""
import logging
from typing import Dict, Tuple

import numpy as np
from gym import Env

from ray.rllib.env.env_context import EnvContext

from coinbase_ml.common import constants as cc
from coinbase_ml.common.actionizers import Actionizer
from coinbase_ml.common.observations import (
    ActionSpace,
    Observation,
    ObservationSpace,
    ObservationSpaceShape,
)
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
        self.action_space = ActionSpace()
        self.config = _config
        self.observation_space = ObservationSpace(
            shape=ObservationSpaceShape(
                account_funds=(1, 4),
                order_book=(1, _config.snapshot_buffer_size * 4 * cc.ORDER_BOOK_DEPTH),
                time_series=(
                    1,
                    _config.snapshot_buffer_size * cc.NUM_CHANNELS_IN_TIME_SERIES,
                ),
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
                reward_strategy=self.config.reward_strategy,
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

    def _exchange_step(self) -> None:
        """
        _exchange_step [summary]
        """
        self.exchange.step()

        if c.VERBOSE:
            interval_end_dt = self.exchange.interval_end_dt
            interval_start_dt = self.exchange.interval_start_dt
            LOGGER.info(
                "Exchange stepped to %s-%s.", interval_start_dt, interval_end_dt
            )

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
            self.exchange.start(
                initial_product_funds=self.config.initial_btc,
                initial_quote_funds=self.config.initial_usd,
                num_warmup_time_steps=self.config.num_warmup_time_steps,
                snapshot_buffer_size=self.config.snapshot_buffer_size,
            )

            self._warmed_up = True
        else:
            self.exchange.reset()

        self._made_illegal_transaction = False

        observation = self.exchange.observation

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
        action.execute()

        self._exchange_step()

        observation = self.exchange.observation
        reward = self.exchange.reward
        self._made_illegal_transaction = self._check_is_out_of_funds()

        if c.VERBOSE:
            LOGGER.info("reward = %s", reward)

        return observation, reward, self.episode_finished, self.exchange.info_dict
