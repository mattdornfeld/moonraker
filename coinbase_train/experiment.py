"""Summary

Attributes:
    SACRED_EXPERIMENT (Experiment): Description
"""
import logging
from datetime import timedelta

from dateutil import parser
from sacred import SETTINGS, Experiment
from sacred.observers import MongoObserver

from coinbase_train import constants as c

SETTINGS["CONFIG"]["READ_ONLY_CONFIG"] = False

SACRED_EXPERIMENT = Experiment(name=c.EXPERIMENT_NAME, interactive=True)
SACRED_EXPERIMENT.observers.append(MongoObserver.create(url=c.MONGO_DB_URL))
SACRED_EXPERIMENT.logger = logging.getLogger(__name__)


@SACRED_EXPERIMENT.config
def config(): # type: ignore
    """This is the default configuration. It's used for local testing.
    """
    initial_btc = "1.000000"
    initial_usd = "10000.00"
    num_time_steps = 3
    num_warmup_time_steps = 1 * num_time_steps
    optimizer_name = "Adam"
    return_value_key = "roi"  # pylint: disable=W0612
    reward_strategy_name = "ProfitRewardStrategy"  # pylint: disable=W0612
    seed = 353523591  # pylint: disable=W0612
    test_end_dt = "2019-01-28 10:10:00.00"
    test_start_dt = "2019-01-28 10:00:00.00"
    time_delta = timedelta(seconds=30)
    time_delta_str = str(time_delta)  # pylint: disable=W0612
    train_end_dt = "2019-01-28 08:20:00.00"
    train_start_dt = "2019-01-28 08:00:00.00"

    hyper_params = dict(  # pylint: disable=W0612
        account_funds_num_units=100,
        account_funds_tower_depth=2,
        batch_size=2 ** 1,
        deep_lob_tower_attention_dim=100,
        deep_lob_tower_conv_block_num_filters=16,
        deep_lob_tower_leaky_relu_slope=0.01,
        discount_factor=0.99,
        gradient_clip=0.1,
        learning_rate=0.001,
        num_actors=1,
        num_epochs_per_iteration=10,
        num_iterations=1,
        optimizer_name=optimizer_name,
        output_tower_depth=3,
        output_tower_num_units=100,
        time_series_tower_attention_dim=100,
        time_series_tower_depth=3,
        time_series_tower_num_filters=17,
        time_series_tower_num_stacks=1,
    )

    train_environment_configs = dict(  # pylint: disable=W0612
        end_dt=parser.parse(train_end_dt),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=1,
        num_time_steps=num_time_steps,
        num_warmup_time_steps=num_warmup_time_steps,
        num_workers=3,
        reward_strategy_name=reward_strategy_name,
        start_dt=parser.parse(train_start_dt),
        time_delta=time_delta,
    )

    test_environment_configs = dict(  # pylint: disable=W0612
        end_dt=parser.parse(test_end_dt),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=1,
        num_time_steps=num_time_steps,
        num_warmup_time_steps=num_warmup_time_steps,
        num_workers=3,
        reward_strategy_name=reward_strategy_name,
        start_dt=parser.parse(test_start_dt),
        time_delta=time_delta,
    )


@SACRED_EXPERIMENT.named_config
def staging(): # type: ignore
    """This configuration will be deployed on merge to master
    """
    initial_btc = "1.000000"
    initial_usd = "10000.00"
    num_time_steps = 100
    num_warmup_time_steps = 100
    optimizer_name = "Adam"
    return_value_key = "roi"  # pylint: disable=W0612
    reward_strategy_name = "ProfitRewardStrategy"  # pylint: disable=W0612
    # seed = randint(int(1e8),int(1e9))
    # seed = 521905088  # pylint: disable=W0612
    test_end_dt = "2019-10-19 19:00:00.00"
    test_start_dt = "2019-10-19 17:00:00.00"
    time_delta = timedelta(seconds=30)
    time_delta_str = str(time_delta)  # pylint: disable=W0612
    train_end_dt = "2019-10-19 17:00:00.00"
    train_start_dt = "2019-10-19 09:00:00.00"

    hyper_params = dict(  # pylint: disable=W0612
        account_funds_num_units=100,
        account_funds_tower_depth=2,
        batch_size=32,
        deep_lob_tower_attention_dim=100,
        deep_lob_tower_conv_block_num_filters=16,
        deep_lob_tower_leaky_relu_slope=0.01,
        discount_factor=0.99,
        gradient_clip=0.1,
        learning_rate=0.001,
        num_actors=1,
        num_epochs_per_iteration=200,
        num_iterations=10,
        optimizer_name=optimizer_name,
        output_tower_depth=3,
        output_tower_num_units=100,
        time_series_tower_attention_dim=100,
        time_series_tower_depth=3,
        time_series_tower_num_filters=16,
        time_series_tower_num_stacks=1,
    )

    train_environment_configs = dict(  # pylint: disable=W0612
        end_dt=parser.parse(train_end_dt),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=1,
        num_time_steps=num_time_steps,
        num_warmup_time_steps=num_warmup_time_steps,
        num_workers=3,
        reward_strategy_name=reward_strategy_name,
        start_dt=parser.parse(train_start_dt),
        time_delta=time_delta,
    )

    test_environment_configs = dict(  # pylint: disable=W0612
        end_dt=parser.parse(test_end_dt),
        initial_btc=initial_btc,
        initial_usd=initial_usd,
        num_episodes=1,
        num_time_steps=num_time_steps,
        num_warmup_time_steps=num_warmup_time_steps,
        num_workers=3,
        reward_strategy_name=reward_strategy_name,
        start_dt=parser.parse(test_start_dt),
        time_delta=time_delta,
    )
