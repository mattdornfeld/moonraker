"""Summary

Attributes:
    ex (Experiment): Description
"""
from datetime import timedelta
import logging

from dateutil import parser
from sacred import Experiment
from sacred.observers import MongoObserver

from coinbase_train import constants as c

ex = Experiment(name=c.EXPERIMENT_NAME)
ex.observers.append(MongoObserver.create(url=c.MONGO_DB_URL))
ex.logger = logging.getLogger(__name__)

@ex.config
def config():
    """This is the default configuration. It's used for local testing.
    """
    initial_btc = '1.000000'
    initial_usd = '10000.00'
    num_warmup_time_steps = 3
    optimizer_name = 'Adam'
    reward_strategy_name = 'ProfitRewardStrategy'  # pylint: disable=W0612
    seed = 353523591  # pylint: disable=W0612
    test_end_dt = '2019-01-28 17:04:00.00'
    test_start_dt = '2019-01-28 17:00:00.00'
    time_delta = timedelta(seconds=30)
    time_delta_str = str(time_delta)  # pylint: disable=W0612
    train_end_dt = '2019-01-28 09:04:00.00'
    train_start_dt = '2019-01-28 09:00:00.00'

    hyper_params = dict(  # pylint: disable=W0612
        account_funds_num_units=100,
        account_funds_tower_depth=2,
        batch_size=4,
        deep_lob_tower_attention_dim=100,
        deep_lob_tower_conv_block_num_filters=16,
        deep_lob_tower_leaky_relu_slope=0.01,
        discount_factor=0.99,
        learning_rate=0.001,
        num_time_steps=3,
        optimizer_name=optimizer_name,
        output_tower_depth=3,
        output_tower_num_units=100,
        time_series_tower_attention_dim=100,
        time_series_tower_depth=3,
        time_series_tower_num_filters=16,
        time_series_tower_num_stacks=1)

    train_environment_configs = dict(  # pylint: disable=W0612
        end_dt=parser.parse(train_end_dt),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=5,
        num_warmup_time_steps=num_warmup_time_steps,
        reward_strategy_name=reward_strategy_name,
        start_dt=parser.parse(train_start_dt),
        time_delta=time_delta
    )

    test_environment_configs = dict(  # pylint: disable=W0612
        end_dt=parser.parse(test_end_dt),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=1,
        num_warmup_time_steps=num_warmup_time_steps,
        reward_strategy_name=reward_strategy_name,
        start_dt=parser.parse(test_start_dt),
        time_delta=time_delta
    )

@ex.named_config
def staging():
    """This configuration will be deployed on merge to master
    """
    initial_btc = '1.000000'
    initial_usd = '10000.00'
    num_warmup_time_steps = 100
    optimizer_name = 'Adam'
    reward_strategy_name = 'ProfitRewardStrategy'  # pylint: disable=W0612
    # seed = 353523591  # pylint: disable=W0612
    test_end_dt = '2019-01-28 19:00:00.00'
    test_start_dt = '2019-01-28 17:00:00.00'
    time_delta = timedelta(seconds=30)
    time_delta_str = str(time_delta)  # pylint: disable=W0612
    train_end_dt = '2019-01-28 17:00:00.00'
    train_start_dt = '2019-01-28 09:00:00.00'

    hyper_params = dict(  # pylint: disable=W0612
        account_funds_num_units=100,
        account_funds_tower_depth=2,
        batch_size=32,
        deep_lob_tower_attention_dim=100,
        deep_lob_tower_conv_block_num_filters=16,
        deep_lob_tower_leaky_relu_slope=0.01,
        discount_factor=0.99,
        learning_rate=0.001,
        num_time_steps=100,
        optimizer_name=optimizer_name,
        output_tower_depth=3,
        output_tower_num_units=100,
        time_series_tower_attention_dim=100,
        time_series_tower_depth=3,
        time_series_tower_num_filters=16,
        time_series_tower_num_stacks=1)

    train_environment_configs = dict(  # pylint: disable=W0612
        end_dt=parser.parse(train_end_dt),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=10,
        num_warmup_time_steps=num_warmup_time_steps,
        reward_strategy_name=reward_strategy_name,
        start_dt=parser.parse(train_start_dt),
        time_delta=time_delta
    )

    test_environment_configs = dict(  # pylint: disable=W0612
        end_dt=parser.parse(test_end_dt),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=1,
        num_warmup_time_steps=num_warmup_time_steps,
        reward_strategy_name=reward_strategy_name,
        start_dt=parser.parse(test_start_dt),
        time_delta=time_delta
    )
