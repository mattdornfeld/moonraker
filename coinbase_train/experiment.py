"""Summary

Attributes:
    ex (Experiment): Description
"""
from datetime import timedelta

from dateutil import parser
from sacred import Experiment
from sacred.observers import MongoObserver

from coinbase_train import constants as c

ex = Experiment()
ex.observers.append(MongoObserver.create(url=c.MONGO_DB_URL))

@ex.config
def config():
    """This is the default configuration. It's used for local testing.
    """
    hyper_params = dict(  # pylint: disable=W0612
        account_funds_num_units=100,
        account_funds_tower_depth=2,
        batch_size=2,
        deep_lob_tower_attention_dim=100,
        deep_lob_tower_conv_block_num_filters=16,
        deep_lob_tower_leaky_relu_slope=0.01,
        discount_factor=0.99,
        learning_rate=0.001,
        num_time_steps=3,
        output_tower_depth=3,
        output_tower_num_units=100,
        time_series_tower_attention_dim=100,
        time_series_tower_depth=3,
        time_series_tower_num_filters=16,
        time_series_tower_num_stacks=1)

    initial_btc = 1.0
    initial_usd = 10000.0
    num_warmup_time_steps = 3
    time_delta = timedelta(seconds=60)

    train_environment_configs = dict(  #pylint: disable=W0612
        end_dt=parser.parse('2019-02-28 04:13:36.79'),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=10,
        num_warmup_time_steps=num_warmup_time_steps,
        start_dt=parser.parse('2019-01-28 03:13:36.79'),
        time_delta=time_delta
        )

    test_environment_configs = dict(  #pylint: disable=W0612
        end_dt=parser.parse('2019-01-28 05:13:36.79'),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=1,
        num_warmup_time_steps=num_warmup_time_steps,
        start_dt=parser.parse('2019-01-28 04:13:36.79'),
        time_delta=time_delta
        )

@ex.named_config
def dev_gpu():
    """This config is used for testing on gpu machines
    """
    hyper_params = dict(  # pylint: disable=W0612
        account_funds_num_units=100,
        account_funds_tower_depth=2,
        batch_size=5,
        deep_lob_tower_attention_dim=100,
        deep_lob_tower_conv_block_num_filters=16,
        deep_lob_tower_leaky_relu_slope=0.01,
        discount_factor=0.99,
        learning_rate=0.001,
        num_time_steps=3,
        output_tower_depth=3,
        output_tower_num_units=100,
        time_series_tower_attention_dim=100,
        time_series_tower_depth=3,
        time_series_tower_num_filters=16,
        time_series_tower_num_stacks=1)

    initial_btc = 1.0
    initial_usd = 10000.0
    num_warmup_time_steps = 3
    time_delta = timedelta(seconds=60)

    train_environment_configs = dict(  # pylint: disable=W0612
        end_dt=parser.parse('2019-02-28 04:13:36.79'),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=10,
        num_warmup_time_steps=num_warmup_time_steps,
        start_dt=parser.parse('2019-01-28 03:13:36.79'),
        time_delta=time_delta
    )

    test_environment_configs = dict(  # pylint: disable=W0612
        end_dt=parser.parse('2019-01-28 05:13:36.79'),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=1,
        num_warmup_time_steps=num_warmup_time_steps,
        start_dt=parser.parse('2019-01-28 04:13:36.79'),
        time_delta=time_delta
    )
