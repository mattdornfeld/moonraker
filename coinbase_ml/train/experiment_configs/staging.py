"""
 [summary]
"""
from datetime import timedelta

from dateutil import parser

from coinbase_ml.train.utils.time_utils import (
    TimeInterval,
    generate_randomly_shifted_lookback_intervals,
)
from coinbase_ml.train.experiment_configs.common import SACRED_EXPERIMENT

# pylint: disable=unused-variable
@SACRED_EXPERIMENT.named_config
def staging():
    """This configuration will be deployed on merge to master
    """
    custom_model_names = ["ActorValueModel"]
    trainer_name = "ppo"
    initial_btc = "1.000000"
    initial_usd = "10000.00"
    num_time_steps = 100
    num_warmup_time_steps = 100
    optimizer_name = "Adam"
    return_value_key = "roi"
    reward_strategy_name = "LogReturnRewardStrategy"
    test_end_dt = "2019-10-20 19:00:00.00"
    test_start_dt = "2019-10-20 17:00:00.00"
    time_delta = timedelta(seconds=30)
    time_delta_str = str(time_delta)

    latest_train_end_dt = "2019-10-20 17:00:00.00"
    latest_train_start_dt = "2019-10-20 09:00:00.00"
    num_lookback_intervals = 5

    train_time_intervals = generate_randomly_shifted_lookback_intervals(
        latest_time_interval=TimeInterval(
            end_dt=parser.parse(latest_train_end_dt),
            start_dt=parser.parse(latest_train_start_dt),
        ),
        num_lookback_intervals=num_lookback_intervals,
    )

    hyper_params = dict(
        account_funds_num_units=100,
        account_funds_tower_depth=2,
        batch_size=2 ** 7,
        deep_lob_tower_attention_dim=100,
        deep_lob_tower_conv_block_num_filters=16,
        deep_lob_tower_leaky_relu_slope=0.01,
        discount_factor=0.99,
        gradient_clip=0.1,
        learning_rate=0.001,
        num_epochs_per_iteration=10,
        num_iterations=10,
        optimizer_name=optimizer_name,
        output_tower_depth=3,
        output_tower_num_units=100,
        time_series_tower_attention_dim=100,
        time_series_tower_depth=3,
        time_series_tower_num_filters=16,
        time_series_tower_num_stacks=1,
    )

    train_environment_configs = dict(
        environment_time_intervals=train_time_intervals,
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_actors=len(train_time_intervals),
        num_episodes=10,
        num_time_steps=num_time_steps,
        num_warmup_time_steps=num_warmup_time_steps,
        num_workers=3,
        reward_strategy_name=reward_strategy_name,
        time_delta=time_delta,
    )

    test_environment_configs = dict(
        environment_time_intervals=[
            TimeInterval(
                end_dt=parser.parse(test_end_dt), start_dt=parser.parse(test_start_dt)
            )
        ],
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_actors=1,
        num_episodes=10,
        num_time_steps=num_time_steps,
        num_warmup_time_steps=num_warmup_time_steps,
        num_workers=3,
        reward_strategy_name=reward_strategy_name,
        time_delta=time_delta,
    )
