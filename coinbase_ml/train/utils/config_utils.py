"""
 [summary]
"""
from collections.abc import ItemsView
from copy import deepcopy
from dataclasses import asdict, dataclass
from datetime import timedelta
from typing import List

from coinbase_ml.common.utils.time_utils import TimeInterval
from coinbase_ml.fakebase.protos import fakebase_pb2  # pylint: disable=unused-import
from coinbase_ml.fakebase.protos.fakebase_pb2 import SimulationType
from coinbase_ml.fakebase.types import ProductVolume, QuoteVolume


@dataclass
class EnvironmentConfigs:
    """Summary
    """

    actionizer: str
    environment_time_intervals: List[TimeInterval]
    initial_usd: QuoteVolume
    initial_btc: ProductVolume
    num_actors: int
    num_episodes: int
    snapshot_buffer_size: int
    num_warmup_time_steps: int
    reward_strategy: str
    time_delta: timedelta
    is_test_environment: bool = False
    enable_progress_bar: bool = False

    @staticmethod
    def _time_delta_key(time_interval: TimeInterval) -> timedelta:
        return time_interval.time_delta

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


@dataclass
class HyperParameters:
    """
    HyperParameters [summary]
    """

    account_funds_num_units: int
    account_funds_tower_depth: int
    batch_size: int
    deep_lob_tower_attention_dim: int
    deep_lob_tower_conv_block_num_filters: int
    deep_lob_tower_leaky_relu_slope: float
    discount_factor: float
    gradient_clip: float
    learning_rate: float
    num_epochs_per_iteration: int
    num_iterations: int
    output_tower_depth: int
    output_tower_num_units: int
    time_series_tower_attention_dim: int
    time_series_tower_depth: int
    time_series_tower_num_filters: int
    time_series_tower_num_stacks: int
