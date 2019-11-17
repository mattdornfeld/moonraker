"""
 [summary]
"""
from ray.rllib.agents import ppo

from ray.rllib.agents import Trainer

from coinbase_train import constants as c
from coinbase_train.environment import Environment
from coinbase_train.utils.config_utils import EnvironmentConfigs, HyperParameters
from coinbase_train.utils.ray_utils import on_episode_end


def build_trainer(
    hyper_params: HyperParameters,
    test_environment_configs: EnvironmentConfigs,
    train_environment_configs: EnvironmentConfigs,
) -> Trainer:
    """
    build_trainer [summary]

    Args:
        hyper_params (HyperParameters): [description]
        test_environment_configs (EnvironmentConfigs): [description]
        train_environment_configs (EnvironmentConfigs): [description]

    Returns:
        Trainer: [description]
    """
    return ppo.PPOTrainer(
        env=Environment,
        config={
            "callbacks": {"on_episode_end": on_episode_end},
            "env_config": train_environment_configs,
            "evaluation_config": {
                "env_config": test_environment_configs,
                "sample_batch_size": test_environment_configs.sample_batch_size,
            },
            "evaluation_interval": 1,
            "evaluation_num_episodes": test_environment_configs.num_episodes,
            "grad_clip": hyper_params.gradient_clip,
            "log_level": "INFO",
            "model": {
                "custom_options": {"hyper_params": hyper_params},
                "custom_model": "CustomModel0",
            },
            "num_cpus_for_driver": 4,
            "num_cpus_per_worker": 2,
            "num_gpus": c.NUM_GPUS,
            "num_workers": train_environment_configs.num_actors,
            "num_sgd_iter": hyper_params.num_epochs_per_iteration,
            "vf_share_layers": True,
            "train_batch_size": train_environment_configs.train_batch_size,
            "sample_batch_size": train_environment_configs.sample_batch_size,
            "sgd_minibatch_size": hyper_params.batch_size,
        },
    )
