"""
 [summary]
"""
from datetime import timedelta

from dateutil import parser

from coinbase_ml.common.models.actor_value import ModelConfigs
from coinbase_ml.common.protos.environment_pb2 import InfoDictKey, RewardStrategy
from coinbase_ml.common.utils.ray_utils import Callbacks
from coinbase_ml.common.utils.time_utils import TimeInterval
from coinbase_ml.train import constants as c
from coinbase_ml.train.environment import Environment, EnvironmentConfigs
from coinbase_ml.train.experiment_configs.common import SACRED_EXPERIMENT


def build_trainer_config(
    model_name: str,
    model_configs: dict,
    test_environment_configs: dict,
    train_environment_configs: dict,
) -> dict:
    _train_environment_configs = EnvironmentConfigs.from_sacred_config(
        train_environment_configs
    )

    return {
        "env": Environment,
        "callbacks": Callbacks,
        "env_config": train_environment_configs,
        "evaluation_config": {
            "env_config": test_environment_configs,
            "worker_side_prioritization": False,
        },
        "evaluation_interval": 1,
        "evaluation_num_episodes": test_environment_configs["num_episodes"],
        "log_level": "INFO",
        "model": {
            "custom_model_config": {
                "model_configs": ModelConfigs.from_sacred_config(model_configs)
            },
            "custom_model": model_name,
        },
        "num_cpus_for_driver": 2,
        "num_cpus_per_worker": 2,
        "num_gpus": c.NUM_GPUS,
        "num_workers": train_environment_configs["num_actors"],
        "rollout_fragment_length": 10,
        "train_batch_size": 2 ** 3,
        "num_sgd_iter": 2,
        "minibatch_buffer_size": 1000,
        "learner_queue_size": 1000,
        "replay_proportion": 0.5,
        "replay_buffer_num_slots": 1000,
        "timesteps_per_iteration": _train_environment_configs.timesteps_per_iteration,
    }


# pylint: disable=unused-variable
@SACRED_EXPERIMENT.named_config
def impala_dev():
    actionizer_name = "coinbase_ml.common.actionizers.EntrySignal"
    model_name = "coinbase_ml.common.models.actor_value.ActorValue"
    initial_product_funds = "0.000000"
    initial_quote_funds = "10000.00"
    num_warmup_time_steps = 3
    num_train_iterations = 3
    result_metric = InfoDictKey.Name(InfoDictKey.portfolioValue)
    reward_strategy = RewardStrategy.Name(RewardStrategy.LogReturnRewardStrategy)
    test_end_dt = "2020-11-19 10:05:00.00"
    test_start_dt = "2020-11-19 10:00:00.00"
    time_delta = 30  # in seconds
    trainer_name = "ray.rllib.agents.impala.ImpalaTrainer"
    train_latest_end_dt = "2020-11-19 09:15:00.00"
    train_latest_start_dt = "2020-11-19 09:00:00.00"
    train_num_actors = 1
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
        num_episodes=10,
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
