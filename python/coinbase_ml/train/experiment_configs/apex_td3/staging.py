"""
 [summary]
"""
from datetime import timedelta

from dateutil import parser

import coinbase_ml.common.constants
from coinbase_ml.common.models.td3_actor_critic import ModelConfigs
from coinbase_ml.common.protos.environment_pb2 import InfoDictKey, RewardStrategy
from coinbase_ml.common.utils.ray_utils import Callbacks
from coinbase_ml.common.utils.time_utils import TimeInterval
from coinbase_ml.train.environment import Environment, EnvironmentConfigs
from coinbase_ml.train.experiment_configs.common import SACRED_EXPERIMENT
from coinbase_ml.train.utils.per_worker_gaussian_noise import PerWorkerGaussianNoise


def build_common_configs(
    model_name: str,
    model_configs: dict,
    test_environment_configs: dict,
    train_environment_configs: dict,
) -> dict:
    """Build configs dict for options common to all trainers

    https://docs.ray.io/en/latest/rllib-training.html#common-parameters
    """
    return {
        "callbacks": Callbacks,
        "env_config": train_environment_configs,
        "evaluation_config": {
            "env_config": test_environment_configs,
            # "timesteps_per_iteration": test_environment_configs.timesteps_per_iteration,
            "worker_side_prioritization": False,
        },
        "evaluation_interval": 1,
        "evaluation_num_episodes": test_environment_configs["num_episodes"],
        "log_level": "ERROR",
        "model": {
            "custom_model_config": {
                "model_configs": ModelConfigs.from_sacred_config(model_configs)
            },
            "custom_model": model_name,
        },
        "num_cpus_for_driver": 4,
        "num_cpus_per_worker": 4,
        "num_gpus": coinbase_ml.common.constants.NUM_GPUS,
        "num_workers": train_environment_configs["num_actors"],
        "train_batch_size": 2 ** 7,
    }


def build_apex_configs(train_environment_configs: dict) -> dict:
    """Build Ape-X specific config dict

    https://docs.ray.io/en/master/rllib-algorithms.html#distributed-prioritized-experience-replay-ape-x
    """
    _train_environment_configs = EnvironmentConfigs.from_sacred_config(
        train_environment_configs
    )
    return {
        "optimizer": {
            "max_weight_sync_delay": 100,
            "num_replay_buffer_shards": 2,
            "debug": False,
        },
        "buffer_size": 100000,
        "learning_starts": 10000,
        "rollout_fragment_length": 10,
        "timesteps_per_iteration": _train_environment_configs.timesteps_per_iteration,
        "worker_side_prioritization": True,
        "min_iter_time_s": 30,
    }


def build_td3_configs() -> dict:
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
            "type": PerWorkerGaussianNoise,
            "random_timesteps": 10000,
            "stddev": 20,
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


def build_trainer_config(
    model_name: str,
    model_configs: dict,
    test_environment_configs: dict,
    train_environment_configs: dict,
) -> dict:
    return {
        "env": Environment,
        **build_common_configs(
            model_name,
            model_configs,
            test_environment_configs,
            train_environment_configs,
        ),
        **build_apex_configs(train_environment_configs),
        **build_td3_configs(),
    }


# pylint: disable=unused-variable
@SACRED_EXPERIMENT.named_config
def apex_td3_staging():
    actionizer_name = "coinbase_ml.common.actionizers.PositionSize"
    model_name = "coinbase_ml.common.models.td3_actor_critic.TD3ActorCritic"
    initial_product_funds = "0.000000"
    initial_quote_funds = "10000.00"
    num_warmup_time_steps = 100
    num_train_iterations = 10
    result_metric = InfoDictKey.Name(InfoDictKey.portfolioValue)
    reward_strategy = RewardStrategy.Name(RewardStrategy.LogReturnRewardStrategy)
    test_end_dt = "2020-11-20 12:00:00.00"
    test_start_dt = "2020-11-20 00:00:00.00"
    time_delta = 30  # in seconds
    trainer_name = "ray.rllib.agents.ddpg.ApexDDPGTrainer"
    train_latest_end_dt = "2020-11-20 00:00:00.00"
    train_latest_start_dt = "2020-11-19 00:00:00.00"
    train_num_actors = 6
    train_time_intervals = train_num_actors * [
        TimeInterval(
            end_dt=parser.parse(train_latest_end_dt),
            start_dt=parser.parse(train_latest_start_dt),
        )
    ]

    model_configs = dict(
        account_funds_num_units=100,
        account_funds_tower_depth=1,
        deep_lob_tower_attention_dim=100,
        deep_lob_tower_conv_block_num_filters=16,
        deep_lob_tower_leaky_relu_slope=0.01,
        output_tower_depth=1,
        output_tower_num_units=100,
        time_series_tower_attention_dim=100,
        time_series_tower_depth=3,
        time_series_tower_num_filters=16,
        time_series_tower_num_stacks=1,
    )

    train_environment_configs = dict(
        actionizer_name=actionizer_name,
        environment_time_intervals=train_time_intervals,
        initial_product_funds=initial_product_funds,
        initial_quote_funds=initial_quote_funds,
        max_negative_roi=0.1,
        num_actors=train_num_actors,
        num_episodes=5,
        num_warmup_time_steps=num_warmup_time_steps,
        reward_strategy=reward_strategy,
        time_delta=timedelta(seconds=time_delta),
        time_series_feature_buffer_size=num_warmup_time_steps,
    )

    test_environment_configs = dict(
        actionizer_name=actionizer_name,
        environment_time_intervals=[
            TimeInterval(
                end_dt=parser.parse(test_end_dt), start_dt=parser.parse(test_start_dt)
            )
        ],
        initial_product_funds=initial_product_funds,
        initial_quote_funds=initial_quote_funds,
        is_test_environment=True,
        num_actors=1,
        num_episodes=1,
        num_warmup_time_steps=num_warmup_time_steps,
        reward_strategy=reward_strategy,
        time_delta=timedelta(seconds=time_delta),
        time_series_feature_buffer_size=num_warmup_time_steps,
    )

    trainer_configs = build_trainer_config(
        model_name, model_configs, test_environment_configs, train_environment_configs
    )
