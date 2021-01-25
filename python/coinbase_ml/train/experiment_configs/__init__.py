"""
Import configs here so they're registered with Sacred
"""
from coinbase_ml.train.experiment_configs.apex_td3.dev import apex_td3_dev
from coinbase_ml.train.experiment_configs.apex_td3.staging import apex_td3_staging
from coinbase_ml.train.experiment_configs.impala.dev import impala_dev
from coinbase_ml.train.experiment_configs.impala.staging import impala_staging
from coinbase_ml.train.experiment_configs.apex_rainbow.dev import apex_rainbow_dev
from coinbase_ml.train.experiment_configs.apex_rainbow.staging import (
    apex_rainbow_staging,
)
