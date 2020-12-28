"""Tests for train.py
"""
from itertools import groupby, product

import pytest
from _pytest.fixtures import SubRequest
from sacred.config.config_scope import ConfigScope

import coinbase_ml.train.experiment_configs as experiment_configs
from coinbase_ml.train.environment import EnvironmentConfigs

DEV_SUFFIX = "_dev"
STAGING_SUFFIX = "_staging"


def _get_config_name(sacred_config: ConfigScope) -> str:
    return sacred_config._func.__name__  # pylint: disable=protected-access


def _get_config_name_base(sacred_config: ConfigScope) -> str:
    return (
        _get_config_name(sacred_config)
        .replace(DEV_SUFFIX, "")
        .replace(STAGING_SUFFIX, "")
    )


def _get_config_name_suffix(sacred_config: ConfigScope) -> str:
    return _get_config_name(sacred_config).split("_")[-1]


EXPERIMENT_CONFIGS = [
    config
    for config in experiment_configs.__dict__.values()
    if isinstance(config, ConfigScope)
]
EXPERIMENT_CONFIGS.sort(key=_get_config_name_base)

# pylint: disable=redefined-outer-name, missing-function-docstring
@pytest.fixture(
    scope="module",
    params=product(
        EXPERIMENT_CONFIGS, ["test_environment_configs", "test_environment_configs"]
    ),
)
def environment_configs(request: SubRequest) -> EnvironmentConfigs:
    """Environment configs fixture
    """
    experiment_config: ConfigScope
    key: str
    experiment_config, key = request.param
    return EnvironmentConfigs(**experiment_config()[key])


@pytest.fixture(
    scope="module", params=product(EXPERIMENT_CONFIGS, ["test_environment_configs"]),
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
    for _, experiment_configs in groupby(EXPERIMENT_CONFIGS, _get_config_name_base):
        dev_config, staging_config = [config() for config in experiment_configs]
        assert dev_config.keys() == staging_config.keys()
        assert (
            dev_config["model_configs"].keys() == staging_config["model_configs"].keys()
        )
        assert (
            dev_config["train_environment_configs"].keys()
            == staging_config["train_environment_configs"].keys()
        )
        assert (
            dev_config["test_environment_configs"].keys()
            == staging_config["test_environment_configs"].keys()
        )
        assert (
            dev_config["trainer_configs"].keys()
            == staging_config["trainer_configs"].keys()
        )
