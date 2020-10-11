"""
 [summary]
"""
from typing import List, Callable, Tuple, Type

from ray.rllib.agents import Trainer
from ray.rllib.utils.typing import PartialTrainerConfigDict

from coinbase_ml.train.trainers import apex_td3, ppo
from coinbase_ml.train.utils.config_utils import EnvironmentConfigs, HyperParameters
from coinbase_ml.train.utils.exception_utils import TrainerNotFoundException

BuildTrainerSignature = Callable[
    [HyperParameters, EnvironmentConfigs, EnvironmentConfigs],
    Tuple[Type[Trainer], PartialTrainerConfigDict],
]


def get_trainer_and_config(
    hyper_params: HyperParameters,
    test_environment_configs: EnvironmentConfigs,
    train_environment_configs: EnvironmentConfigs,
    trainer_name: str,
) -> Tuple[Type[Trainer], PartialTrainerConfigDict]:
    """Gets trainer and builds configs for specified arguments
    """

    try:
        build_config_func: BuildTrainerSignature = globals()[
            trainer_name
        ].__getattribute__("get_trainer_and_config")
    except KeyError:
        raise TrainerNotFoundException(trainer_name) from KeyError

    return build_config_func(
        hyper_params, test_environment_configs, train_environment_configs
    )
