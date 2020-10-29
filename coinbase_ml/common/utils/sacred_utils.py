"""
sacred_utils.py
"""
import logging
from math import isnan
from typing import Dict, Optional

from incense import ExperimentLoader
from incense.experiment import Experiment as ExperimentResults
from sacred import SETTINGS, Experiment
from sacred.observers import MongoObserver

from coinbase_ml.common import constants as c


def create_sacred_experiment(experiment_name: str) -> Experiment:
    """
    create_sacred_experiment [summary]

    Args:
        experiment_name (str): [description]

    Returns:
        Experiment: [description]
    """
    SETTINGS["CONFIG"]["READ_ONLY_CONFIG"] = False
    sacred_experiment = Experiment(name=experiment_name)
    sacred_experiment.observers.append(MongoObserver.create(url=c.MONGO_DB_URL))
    sacred_experiment.logger = logging.getLogger(__name__)
    sacred_experiment.captured_out_filter = (
        lambda captured_output: "Output capturing disabled."
    )

    return sacred_experiment


def get_sacred_experiment_results(experiment_id: int) -> ExperimentResults:
    """
    get_sacred_experiment_results [summary]

    Args:
        experiment_id (int): [description]

    Returns:
        ExperimentResults: [description]
    """
    loader = ExperimentLoader(mongo_uri=c.MONGO_DB_URL, db_name=c.SACRED_DB_NAME)

    return loader.find_by_id(experiment_id)


def log_metrics_to_sacred(
    experiment: Experiment, metrics: Dict[str, float], prefix: Optional[str] = None
) -> None:
    """
    log_metrics_to_sacred [summary]

    Args:
        experiment (Experiment): [description]
        metrics (Dict[str, float]): [description]
        prefix (Optional[str]): [description]
    """
    for metric_name, metric in metrics.items():
        if isinstance(metric, (int, float)):
            _metric_name = f"{prefix}_{metric_name}" if prefix else metric_name
            experiment.log_scalar(_metric_name, 0.0 if isnan(metric) else metric)
