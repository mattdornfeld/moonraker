"""
 [summary]
"""
from datetime import timedelta

from dateutil import parser

from coinbase_ml.common.protos.environment_pb2 import (
    Actionizer,
    InfoDictKey,
    RewardStrategy,
)
from coinbase_ml.common.utils.time_utils import (
    TimeInterval,
    generate_randomly_shifted_lookback_intervals,
)
from coinbase_ml.train.experiment_configs.common import SACRED_EXPERIMENT

# pylint: disable=unused-variable
@SACRED_EXPERIMENT.config
def config():
    """This is the default configuration. It's used for local testing.
    """
    custom_model_names = ["TD3ActorCritic"]
    trainer_name = "apex_td3"
    initial_btc = "1.000000"
    initial_usd = "10000.00"
    snapshot_buffer_size = 3
    num_warmup_time_steps = snapshot_buffer_size
    result_metric = InfoDictKey.Name(InfoDictKey.portfolioValue)
    actionizer = Actionizer.Name(Actionizer.SignalPositionSize)
    reward_strategy = RewardStrategy.Name(RewardStrategy.LogReturnRewardStrategy)
    test_end_dt = "2020-11-19 10:05:00.00"
    test_start_dt = "2020-11-19 10:00:00.00"
    time_delta = timedelta(seconds=30)
    time_delta_str = str(time_delta)

    latest_train_end_dt = "2020-11-19 09:15:00.00"
    latest_train_start_dt = "2020-11-19 09:00:00.00"
    num_actors = 1

    train_time_intervals = generate_randomly_shifted_lookback_intervals(
        latest_time_interval=TimeInterval(
            end_dt=parser.parse(latest_train_end_dt),
            start_dt=parser.parse(latest_train_start_dt),
        ),
        num_lookback_intervals=num_actors,
    )

    hyper_params = dict(
        account_funds_num_units=100,
        account_funds_tower_depth=1,
        batch_size=2 ** 3,
        deep_lob_tower_attention_dim=100,
        deep_lob_tower_conv_block_num_filters=16,
        deep_lob_tower_leaky_relu_slope=0.01,
        discount_factor=0.99,
        gradient_clip=0.1,
        learning_rate=0.001,
        num_epochs_per_iteration=10,
        num_iterations=2,
        output_tower_depth=1,
        output_tower_num_units=100,
        time_series_tower_attention_dim=100,
        time_series_tower_depth=3,
        time_series_tower_num_filters=17,
        time_series_tower_num_stacks=1,
    )

    train_environment_configs = dict(
        actionizer=actionizer,
        environment_time_intervals=train_time_intervals,
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_actors=num_actors,
        num_episodes=1,
        snapshot_buffer_size=snapshot_buffer_size,
        num_warmup_time_steps=num_warmup_time_steps,
        reward_strategy=reward_strategy,
        time_delta=time_delta,
    )

    test_environment_configs = dict(
        actionizer=actionizer,
        environment_time_intervals=[
            TimeInterval(
                end_dt=parser.parse(test_end_dt), start_dt=parser.parse(test_start_dt)
            )
        ],
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_actors=1,
        num_episodes=2,
        snapshot_buffer_size=snapshot_buffer_size,
        num_warmup_time_steps=num_warmup_time_steps,
        reward_strategy=reward_strategy,
        time_delta=time_delta,
    )
