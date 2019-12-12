"""
 [summary]
"""
import os
from datetime import datetime, timedelta

from dateutil.tz import UTC

from coinbase_ml.serve.experiment_configs.common import SACRED_EXPERIMENT

# pylint: disable=unused-variable
@SACRED_EXPERIMENT.config
def config():
    """
    config is the default config for serve jobs.
    These settings are used for testing.
    """
    train_experiment_id = int(os.environ.get("TRAIN_EXPERIMENT_ID", "-1"))
    # train_experiment_id = 13

    # exchange configs
    serving_run_start_dt = datetime.now(UTC)
    serving_run_length = timedelta(minutes=3)
    serving_run_end_dt = serving_run_start_dt + serving_run_length
    time_delta = timedelta(seconds=5)

    serving_run_start_dt_str = str(serving_run_start_dt)
    serving_run_length_str = str(serving_run_length)
    serving_run_end_dt_str = str(serving_run_end_dt)
