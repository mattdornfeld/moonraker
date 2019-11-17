"""
 [summary]
"""
from typing import List, Callable

from ray.rllib.agents import Trainer

from coinbase_train.trainers import ppo
from coinbase_train.utils.config_utils import EnvironmentConfigs, HyperParameters
from coinbase_train.utils.exception_utils import TrainerNotFoundException

BuildTrainerSignature = Callable[
    [HyperParameters, EnvironmentConfigs, EnvironmentConfigs], Trainer
]


def get_and_build_trainer(
    hyper_params: HyperParameters,
    test_environment_configs: EnvironmentConfigs,
    train_environment_configs: EnvironmentConfigs,
    trainer_name: str,
) -> Trainer:
    """
    get_and_build_trainer [summary]

    Args:
        hyper_params (HyperParameters): [description]
        test_environment_configs (EnvironmentConfigs): [description]
        train_environment_configs (EnvironmentConfigs): [description]
        trainer_name (str): [description]

    Raises:
        TrainerNotFoundException: [description]

    Returns:
        Trainer: [description]
    """

    try:
        build_trainer_func: BuildTrainerSignature = globals()[
            trainer_name
        ].__getattribute__("build_trainer")
    except KeyError:
        raise TrainerNotFoundException(trainer_name)

    return build_trainer_func(
        hyper_params, test_environment_configs, train_environment_configs
    )
