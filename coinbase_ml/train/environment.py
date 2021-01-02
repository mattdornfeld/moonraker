"""Summary
"""
import logging
from collections.abc import ItemsView
from copy import deepcopy
from dataclasses import asdict, dataclass
from datetime import timedelta
from typing import Dict, List, Tuple, Union

import numpy as np
from gym import Env
from nptyping import NDArray
from ray.rllib.env.env_context import EnvContext

from coinbase_ml.common import constants as cc
from coinbase_ml.common.actionizers import Actionizer
from coinbase_ml.common.observations import (
    Observation,
    ObservationSpace,
    ObservationSpaceShape,
)
from coinbase_ml.common.protos.environment_pb2 import InfoDictKey
from coinbase_ml.common.utils.ray_utils import get_actionizer
from coinbase_ml.common.utils.time_utils import TimeInterval
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.fakebase.protos import fakebase_pb2  # pylint: disable=unused-import
from coinbase_ml.fakebase.protos.fakebase_pb2 import SimulationType
from coinbase_ml.fakebase.types import ProductVolume, QuoteVolume
from coinbase_ml.train import constants as c
from coinbase_ml.train.utils.exception_utils import EnvironmentFinishedException

LOGGER = logging.getLogger(__name__)


@dataclass
class EnvironmentConfigs:
    """Summary
    """

    actionizer_name: str
    environment_time_intervals: List[TimeInterval]
    initial_product_funds: ProductVolume
    initial_quote_funds: QuoteVolume
    num_actors: int
    num_episodes: int
    num_warmup_time_steps: int
    reward_strategy: str
    time_delta: timedelta
    time_series_feature_buffer_size: int
    is_test_environment: bool = False
    enable_progress_bar: bool = False
    max_negative_roi: float = 0.99

    @staticmethod
    def _time_delta_key(time_interval: TimeInterval) -> timedelta:
        return time_interval.time_delta

    @property
    def actionizer(self) -> Actionizer:
        return get_actionizer(self.actionizer_name)

    @staticmethod
    def from_sacred_config(environment_configs: dict) -> "EnvironmentConfigs":
        _environment_configs = deepcopy(environment_configs)
        _environment_configs["initial_product_funds"] = cc.PRODUCT_CURRENCY.volume_type(
            _environment_configs["initial_product_funds"]
        )
        _environment_configs["initial_quote_funds"] = cc.QUOTE_CURRENCY.volume_type(
            _environment_configs["initial_quote_funds"]
        )
        return EnvironmentConfigs(**_environment_configs)

    def items(self) -> ItemsView:
        """
        items [summary]

        Returns:
            ItemsView[str, Any]: [description]
        """
        return asdict(self).items()

    @property
    def num_max_episode_steps(self) -> int:
        """
        The max number of steps in an episodes. Calculated by returning the maximum
        number of steps the environment with the largest time interval can take.
        """
        environment_time_intervals: List[TimeInterval] = deepcopy(
            self.environment_time_intervals
        )
        environment_time_intervals.sort(key=self._time_delta_key)
        largest_time_interval = environment_time_intervals[-1]

        return (
            int(largest_time_interval.time_delta / self.time_delta)
            - self.num_warmup_time_steps
            - 1
        )

    @property
    def num_max_episode_steps_per_rollout(self) -> int:
        """
        The max number of steps in an episode multiplied by the number of actors
        in the rollout set.
        """
        return self.num_actors * self.num_max_episode_steps

    @property
    def simulation_type(self) -> "fakebase_pb2.SimulationTypeValue":
        """
        Either SimulationType.evaluation or SimulationType.train
        """
        return (
            SimulationType.evaluation
            if self.is_test_environment
            else SimulationType.train
        )

    @property
    def timesteps_per_iteration(self) -> int:
        """
        The number of training samples gathered in an iteration.
        """
        return self.num_episodes * self.num_max_episode_steps_per_rollout


class Environment(Env):  # pylint: disable=W0223
    """
    Environment [summary]
    """

    def __init__(self, config: EnvContext) -> None:
        self._warmed_up = False
        self.config = EnvironmentConfigs.from_sacred_config(config)
        self.actionizer = self.config.actionizer
        self.action_space = self.actionizer.action_space
        self.episode_number = 0
        self.is_test_environment = self.config.is_test_environment
        self.worker_index = config.worker_index
        self.observation_space = ObservationSpace(
            shape=ObservationSpaceShape(
                account_funds=(1, 4),
                order_book=(
                    1,
                    self.config.time_series_feature_buffer_size
                    * 4
                    * cc.ORDER_BOOK_DEPTH,
                ),
                time_series=(
                    1,
                    self.config.time_series_feature_buffer_size
                    * cc.NUM_CHANNELS_IN_TIME_SERIES,
                ),
            )
        )

        # Ray does something weird where it creates a local copy of the environment with
        # config.worker_index=0 but it doesn't seem to actually use it. This block of code
        # takes care of that case.
        if (
            config.worker_index == 0
            and not self.is_test_environment
            and self.config.num_actors != 0
        ):
            self._start_dt = None
            self._end_dt = None
            self.exchange = None
        else:
            worker_index = (
                config.worker_index
                if self.is_test_environment
                else config.worker_index - 1
            )
            environment_time_interval = self.config.environment_time_intervals[
                worker_index
            ]
            self._start_dt = environment_time_interval.start_dt
            self._end_dt = environment_time_interval.end_dt
            self.exchange = Exchange(
                end_dt=self._end_dt,
                product_id=cc.PRODUCT_ID,
                start_dt=self._start_dt,
                time_delta=self.config.time_delta,
                reward_strategy=self.config.reward_strategy,
                actionizer=self.actionizer.proto_value,
            )
            self.reset()

    def _exchange_step(self, action: NDArray[float]) -> None:
        """
        _exchange_step [summary]

        Args:
            action (NDArray[float]): [description]
        """
        self.exchange.step(actor_output=action)

        if c.VERBOSE:
            interval_end_dt = self.exchange.interval_end_dt
            interval_start_dt = self.exchange.interval_start_dt
            LOGGER.info(
                "Exchange stepped to %s-%s.", interval_start_dt, interval_end_dt
            )

    def _should_backup_to_cloud_storage(self) -> bool:
        return self.worker_index in [0, 1]

    def _should_end_early(self) -> bool:
        """Summary

        Returns:
            bool: Description
        """
        return (
            not self.is_test_environment
            and self.exchange.info_dict[InfoDictKey.Name(InfoDictKey.roi)]
            < -self.config.max_negative_roi
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
        return self.exchange.finished or self._should_end_early()

    def reset(self) -> Observation:
        """Summary

        Returns:
            Observation: Description
        """
        self.episode_number += 1
        if c.VERBOSE:
            LOGGER.info("Resetting the environment.")

        if not self._warmed_up:
            self.exchange.start(
                initial_product_funds=self.config.initial_product_funds,
                initial_quote_funds=self.config.initial_quote_funds,
                num_warmup_time_steps=self.config.num_warmup_time_steps,
                snapshot_buffer_size=self.config.time_series_feature_buffer_size,
                enable_progress_bar=self.config.enable_progress_bar,
                simulation_type=self.config.simulation_type,
                backup_to_cloud_storage=self._should_backup_to_cloud_storage(),
            )

            self._warmed_up = True
        else:
            self.exchange.reset()

        observation = self.exchange.observation

        if c.VERBOSE:
            LOGGER.info("Environment reset.")

        return observation

    def step(
        self, action: Union[NDArray[float], np.int64]
    ) -> Tuple[Observation, float, bool, Dict[str, float]]:
        """Summary

        Args:
            action (NDArray[float]): Description

        Returns:
            Tuple[Observation, float, bool, Dict]: Description

        Raises:
            EnvironmentFinishedException: Description
        """
        if self.episode_finished:
            raise EnvironmentFinishedException

        _action = np.array([action]) if isinstance(action, np.int64) else action
        self._exchange_step(_action)

        observation = self.exchange.observation
        reward = self.exchange.reward

        if c.VERBOSE:
            LOGGER.info("reward = %s", reward)

        return observation, reward, self.episode_finished, self.exchange.info_dict
