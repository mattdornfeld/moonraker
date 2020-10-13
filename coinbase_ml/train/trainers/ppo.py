"""
 [summary]
"""
from typing import Any, Dict, Tuple, Type

from ray.rllib.agents import Trainer, ppo
from ray.rllib.utils.typing import PartialTrainerConfigDict

from coinbase_ml.common.utils.ray_utils import Callbacks
from coinbase_ml.train import constants as c
from coinbase_ml.train.environment import Environment
from coinbase_ml.train.utils.config_utils import EnvironmentConfigs, HyperParameters


def build_common_configs(
    hyper_params: HyperParameters,
    test_environment_configs: EnvironmentConfigs,
    train_environment_configs: EnvironmentConfigs,
) -> Dict[str, Any]:
    """Build configs dict for options common to all trainers

    https://docs.ray.io/en/latest/rllib-training.html#common-parameters
    """
    return {
        "callbacks": Callbacks,
        "env_config": train_environment_configs.__dict__,
        "evaluation_config": {
            "env_config": test_environment_configs.__dict__,
            "rollout_fragment_length": test_environment_configs.num_max_episode_steps_per_rollout,
        },
        "evaluation_interval": 1,
        "evaluation_num_episodes": test_environment_configs.num_episodes,
        "log_level": "INFO",
        "model": {
            "custom_model_config": {"hyper_params": hyper_params},
            "custom_model": "CustomModel0",
        },
        "num_cpus_for_driver": 2,
        "num_cpus_per_worker": 2,
        "num_gpus": c.NUM_GPUS,
        "num_workers": train_environment_configs.num_actors,
        "rollout_fragment_length": train_environment_configs.num_max_episode_steps_per_rollout,
        "train_batch_size": hyper_params.batch_size,
    }


def get_trainer_and_config(
    hyper_params: HyperParameters,
    test_environment_configs: EnvironmentConfigs,
    train_environment_configs: EnvironmentConfigs,
) -> Tuple[Type[Trainer], PartialTrainerConfigDict]:
    """Gets trainer and builds configs for ppo trainer
    """
    config_dict = PartialTrainerConfigDict(
        {
            **build_common_configs(
                hyper_params, test_environment_configs, train_environment_configs
            ),
            "env": Environment,
            "grad_clip": hyper_params.gradient_clip,
            "model": {
                "custom_model_config": {"hyper_params": hyper_params},
                "custom_model": "CustomModel0",
            },
            "num_sgd_iter": hyper_params.num_epochs_per_iteration,
            "vf_share_layers": True,
            "sgd_minibatch_size": hyper_params.batch_size,
        }
    )

    return ppo.PPOTrainer, config_dict
