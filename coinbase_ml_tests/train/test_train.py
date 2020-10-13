"""Tests for train.py
"""
import pytest
from _pytest.fixtures import SubRequest
from sacred.config.config_scope import ConfigScope

from coinbase_ml.train.experiment_configs import config as default, staging
from coinbase_ml.train.utils.config_utils import EnvironmentConfigs

# pylint: disable=redefined-outer-name, missing-function-docstring
@pytest.fixture(
    scope="module",
    params=[
        (default, "test_environment_configs"),
        (staging, "test_environment_configs"),
        (default, "train_environment_configs"),
        (staging, "train_environment_configs"),
    ],
)
def environment_configs(request: SubRequest) -> EnvironmentConfigs:
    """Environment configs fixture
    """
    experiment_config: ConfigScope
    key: str
    experiment_config, key = request.param
    return EnvironmentConfigs(**experiment_config()[key])


@pytest.fixture(
    scope="module",
    params=[
        (default, "train_environment_configs"),
        (staging, "train_environment_configs"),
    ],
)
def train_environment_configs(request: SubRequest) -> EnvironmentConfigs:
    """Train environment configs fixture
    """
    experiment_config: ConfigScope
    key: str
    experiment_config, key = request.param
    return EnvironmentConfigs(**experiment_config()[key])


def test_num_max_episode_steps_per_rollout_greater_than_zero(
    environment_configs: EnvironmentConfigs,
) -> None:

    assert environment_configs.num_max_episode_steps_per_rollout > 0


def test_num_max_episode_steps_per_rollout_less_then_timesteps_per_iteration(
    train_environment_configs: EnvironmentConfigs,
) -> None:
    assert (
        train_environment_configs.num_max_episode_steps_per_rollout
        <= train_environment_configs.timesteps_per_iteration
    )


def test_config_schema() -> None:
    defualt_config = default()
    staging_config = staging()
    assert defualt_config.keys() == staging_config.keys()
    assert (
        defualt_config["hyper_params"].keys() == staging_config["hyper_params"].keys()
    )
    assert (
        defualt_config["train_environment_configs"].keys()
        == staging_config["train_environment_configs"].keys()
    )
    assert (
        defualt_config["test_environment_configs"].keys()
        == staging_config["test_environment_configs"].keys()
    )
