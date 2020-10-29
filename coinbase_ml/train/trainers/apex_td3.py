"""
 [summary]
"""
from typing import Any, Dict, Tuple, Type

from ray.rllib.agents import ddpg, Trainer
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
            "timesteps_per_iteration": test_environment_configs.timesteps_per_iteration,
            "worker_side_prioritization": False,
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
        "train_batch_size": hyper_params.batch_size,
    }


def build_apex_configs(
    hyper_params: HyperParameters, train_environment_configs: EnvironmentConfigs
) -> Dict[str, Any]:
    """Build Ape-X specific config dict

    https://docs.ray.io/en/master/rllib-algorithms.html#distributed-prioritized-experience-replay-ape-x
    """
    return {
        "optimizer": {
            "max_weight_sync_delay": 100,
            "num_replay_buffer_shards": 2,
            "debug": False,
        },
        "buffer_size": 100000,
        "learning_starts": hyper_params.batch_size,
        "rollout_fragment_length": 10,
        "timesteps_per_iteration": train_environment_configs.timesteps_per_iteration,
        "worker_side_prioritization": True,
        "min_iter_time_s": 30,
    }


def build_td3_configs() -> Dict[str, Any]:
    """Build TD3 specific config dict

    https://docs.ray.io/en/latest/rllib-algorithms.html#deep-deterministic-policy-gradients-ddpg-td3
    """
    return {
        "twin_q": True,
        "policy_delay": 2,
        "smooth_target_policy": True,
        "target_noise": 0.2,
        "target_noise_clip": 0.5,
        "exploration_config": {
            "type": "GaussianNoise",
            "random_timesteps": 100,
            "stddev": 0.1,
            "initial_scale": 1.0,
            "final_scale": 1.0,
            "scale_timesteps": 1,
        },
        "n_step": 1,
        "gamma": 0.99,
        "actor_lr": 1e-3,
        "critic_lr": 1e-3,
        "l2_reg": 0.0,
        "tau": 5e-3,
        "use_huber": False,
        "target_network_update_freq": 0,
    }


def get_trainer_and_config(
    hyper_params: HyperParameters,
    test_environment_configs: EnvironmentConfigs,
    train_environment_configs: EnvironmentConfigs,
) -> Tuple[Type[Trainer], PartialTrainerConfigDict]:
    """Gets trainer and builds configs for apex_td3 trainer
    """
    config_dict = PartialTrainerConfigDict(
        {
            "env": Environment,
            **build_common_configs(
                hyper_params, test_environment_configs, train_environment_configs
            ),
            **build_apex_configs(hyper_params, train_environment_configs),
            **build_td3_configs(),
        }
    )

    return ddpg.ApexDDPGTrainer, config_dict
