"""Summary
"""
import logging
from pathlib import Path
from shutil import rmtree
from typing import List

import ray
from ray import tune
from ray.tune.checkpoint_manager import Checkpoint
from ray.tune.trial import Trial
from sacred.run import Run

from coinbase_ml.common import constants as cc
from coinbase_ml.common.utils.gcs_utils import get_gcs_base_path, upload_file_to_gcs
from coinbase_ml.common.utils.ray_utils import get_trainer, register_custom_model
from coinbase_ml.train import constants as c
from coinbase_ml.train.experiment_configs.common import SACRED_EXPERIMENT
from coinbase_ml.train.utils.sacred_logger import SacredLogger


LOGGER = logging.getLogger(__name__)


def _get_result_metric_key(result_metric: str, aggregator: str = "mean") -> str:
    if aggregator not in ["mean", "min", "max"]:
        raise ValueError

    return f"{result_metric}_{aggregator}"


@SACRED_EXPERIMENT.main
def main(
    _run: Run,
    model_name: str,
    num_train_iterations: int,
    result_metric: str,
    trainer_name: str,
    trainer_configs: dict,
) -> float:
    result_metric_key = f"{result_metric}_mean"

    ray.init(
        address=c.RAY_REDIS_ADDRESS,
        object_store_memory=c.RAY_OBJECT_STORE_MEMORY,
        # local_mode=c.LOCAL_MODE,
    )

    sacred_logger = SacredLogger()
    sacred_logger.start()

    register_custom_model(model_name)
    trainer = get_trainer(trainer_name)
    result_metric_key = _get_result_metric_key(result_metric)

    experiment_analysis = tune.run(
        checkpoint_freq=1,
        config=trainer_configs,
        metric=result_metric_key,
        mode="max",
        run_or_experiment=trainer,
        stop={"training_iteration": num_train_iterations},
    )

    trial: Trial = experiment_analysis.trials[0]
    checkpoints: List[Checkpoint] = trial.checkpoint_manager.best_checkpoints()
    checkpoints.sort(key=lambda c: c.result["custom_metrics"][result_metric_key])
    best_checkpoint = checkpoints[-1]

    best_checkpoint_path = Path(best_checkpoint.value)
    path_keys = [
        (best_checkpoint_path, f"{get_gcs_base_path(_run)}/rllib_checkpoint"),
        (
            best_checkpoint_path.with_suffix(".tune_metadata"),
            f"{get_gcs_base_path(_run)}/rllib_checkpoint.tune_metadata",
        ),
        (
            best_checkpoint_path.parent.parent / "params.pkl",
            f"{get_gcs_base_path(_run)}/trainer_config.pkl",
        ),
    ]

    for (path, gcs_key) in path_keys:
        upload_file_to_gcs(
            bucket_name=cc.MODEL_BUCKET_NAME,
            credentials_path=cc.SERVICE_ACCOUNT_JSON,
            filename=path,
            gcp_project_name=cc.GCP_PROJECT_NAME,
            key=gcs_key,
        )

    rmtree(trial.logdir, ignore_errors=True)

    _run.info["model_bucket_name"] = cc.MODEL_BUCKET_NAME
    _run.info["checkpoint_gcs_key"] = path_keys[0][1]
    _run.info["checkpoint_metadata_gcs_key"] = path_keys[1][1]
    _run.info["trainer_config_gcs_key"] = path_keys[2][1]

    return float(
        best_checkpoint.result["evaluation"]["custom_metrics"][result_metric_key]
    )


if __name__ == "__main__":
    SACRED_EXPERIMENT.run_commandline()
