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
    """Configuration variables recorded in Sacred. These will be
    automatically passed to the main function.
    """
    hyper_params = dict(  #pylint: disable=W0612
        attention_dim=50,
        batch_size=1,
        depth=2,
        learning_rate=0.001,
        num_filters=100,
        num_stacks=1,
        num_time_steps=20)  

    train_environment_configs = dict(  #pylint: disable=W0612
        end_dt=parser.parse('2019-01-28 04:13:36.79'),
        initial_btc=0,
        initial_usd=10000,
        num_episodes=10,
        start_dt=parser.parse('2019-01-28 03:13:36.79'),
        time_delta=timedelta(seconds=10)
        )

    test_environment_configs = dict(  #pylint: disable=W0612
        end_dt=parser.parse('2019-01-28 05:13:36.79'),
        initial_btc=0,
        initial_usd=10000,
        num_episodes=1,
        start_dt=parser.parse('2019-01-28 04:13:36.79'),
        time_delta=timedelta(seconds=10)
        )
