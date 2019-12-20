"""
 [summary]
"""
import os
from datetime import datetime, timedelta

from dateutil.tz import UTC

from coinbase_ml.serve.experiment_configs.common import SACRED_EXPERIMENT

# pylint: disable=unused-variable
@SACRED_EXPERIMENT.named_config
def staging():
    """
    staging is the config that should be deployed from the master branch
    """
    train_experiment_id = int(os.environ.get("TRAIN_EXPERIMENT_ID", "-1"))

    # exchange configs
    serving_run_start_dt = datetime.now(UTC)
    serving_run_length = timedelta(minutes=120)
    serving_run_end_dt = serving_run_start_dt + serving_run_length
    # This should be left as None except for testing. When left as None
    # it will use the time_delta from the train_experiment config.
    time_delta = None

    serving_run_start_dt_str = str(serving_run_start_dt)
    serving_run_length_str = str(serving_run_length)
    serving_run_end_dt_str = str(serving_run_end_dt)
