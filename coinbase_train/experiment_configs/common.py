"""Summary

Attributes:
    SACRED_EXPERIMENT (Experiment): Description
"""
import logging

from sacred import SETTINGS, Experiment
from sacred.observers import MongoObserver

from coinbase_train import constants as c

SETTINGS["CONFIG"]["READ_ONLY_CONFIG"] = False

SACRED_EXPERIMENT = Experiment(name=c.EXPERIMENT_NAME, interactive=True)
SACRED_EXPERIMENT.observers.append(MongoObserver.create(url=c.MONGO_DB_URL))
SACRED_EXPERIMENT.logger = logging.getLogger(__name__)
