"""
 [summary]
"""
from collections.abc import ItemsView
from copy import deepcopy
from dataclasses import asdict, dataclass
from datetime import timedelta
from decimal import Decimal
from typing import List, Type


from coinbase_ml.common.reward import BaseRewardStrategy
from coinbase_ml.train.utils.time_utils import TimeInterval


@dataclass
class EnvironmentConfigs:
    """Summary
    """

    environment_time_intervals: List[TimeInterval]
    initial_usd: Decimal
    initial_btc: Decimal
    num_actors: int
    num_episodes: int
    num_time_steps: int
    num_warmup_time_steps: int
    num_workers: int
    reward_strategy: Type[BaseRewardStrategy]
    time_delta: timedelta
    is_test_environment: bool = False

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
        nb_max_episode_steps returns the max number of steps in an episodes.
        Calculated by returning the maximum number of steps the environment
        with the largest time interval can take.

        Returns:
            int: [description]
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
    def sample_batch_size(self) -> int:
        """
        sample_batch_size is the number of samples a set of rollout workers
        will collect before terminating their episodes and starting the next sample.
        This is set so each rollout worker will finish one episode before starting the
        next one.

        Returns:
            int: [description]
        """
        return self.num_actors * self.num_max_episode_steps

    @property
    def train_batch_size(self) -> int:
        """
        train_batch_size is the number of training samples in an iteration. The
        trainer will collect sample batches until it has this number of samples.
        At that point it will do SGD on the samples, updating the model parameters.

        Returns:
            int: [description]
        """
        return self.num_episodes * self.sample_batch_size


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
    optimizer_name: str
    output_tower_depth: int
    output_tower_num_units: int
    time_series_tower_attention_dim: int
    time_series_tower_depth: int
    time_series_tower_num_filters: int
    time_series_tower_num_stacks: int
