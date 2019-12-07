"""Summary

Attributes:
    SACRED_EXPERIMENT (Experiment): Description
"""
from coinbase_ml.common.utils.sacred_utils import create_sacred_experiment
from coinbase_ml.serve import constants as c

SACRED_EXPERIMENT = create_sacred_experiment(c.EXPERIMENT_NAME)
