"""Summary
"""
import logging
from pathlib import Path
from shutil import rmtree
from typing import Any, Dict, List


import ray
from ray import tune
from ray.tune.checkpoint_manager import Checkpoint
from ray.tune.trial import Trial
from ray.rllib.models.tf.tf_modelv2 import TFModelV2
from sacred.run import Run

from coinbase_ml.common import constants as cc
from coinbase_ml.common import models
from coinbase_ml.common.utils.gcs_utils import get_gcs_base_path, upload_file_to_gcs
from coinbase_ml.common.utils.ray_utils import register_custom_models
from coinbase_ml.train import constants as c
from coinbase_ml.train.experiment_configs.common import SACRED_EXPERIMENT
from coinbase_ml.train.trainers import get_trainer_and_config
from coinbase_ml.train.utils.config_utils import EnvironmentConfigs, HyperParameters
from coinbase_ml.train.utils.sacred_logger import SacredLogger


LOGGER = logging.getLogger(__name__)


@SACRED_EXPERIMENT.main
def main(
    _run: Run,
    custom_model_names: List[str],
    hyper_params: Dict[str, Any],
    result_metric: str,
    test_environment_configs: Dict[str, Any],
    train_environment_configs: Dict[str, Any],
    trainer_name: str,
) -> float:
    """
    main builds an agent, trains on the train environment, evaluates on the test
    environment, saves artifacts to gcs. Logs results to Sacred.

    Args:
        _run (Run): [description]
        hyper_params (dict): [description]
        result_metric (str): Metric recorded as result in Sacred.
            Note that what's actually recorded is the mean of that metric,
            averaged over all episodes in an iteration.
        seed (int): [description]
        test_environment_configs (Dict[str, Any]): [description]
        train_environment_configs (Dict[str, Any]): [description]

    Returns:
        float: [description]
    """
    result_metric_key = f"{result_metric}_mean"

    for configs in [test_environment_configs, train_environment_configs]:
        configs["reward_strategy"] = configs.pop("reward_strategy")
        configs["initial_usd"] = cc.PRODUCT_ID.quote_volume_type(configs["initial_usd"])
        configs["initial_btc"] = cc.PRODUCT_ID.quote_volume_type(configs["initial_btc"])

    ray.init(
        address=c.RAY_REDIS_ADDRESS,
        include_dashboard=c.RAY_INCLUDE_WEBUI,
        object_store_memory=c.RAY_OBJECT_STORE_MEMORY,
    )

    custom_models: List[TFModelV2] = [
        models.__dict__[model_name] for model_name in custom_model_names
    ]
    register_custom_models(custom_models)

    trainer, trainer_config = get_trainer_and_config(
        hyper_params=HyperParameters(**hyper_params),
        test_environment_configs=EnvironmentConfigs(
            is_test_environment=True, **test_environment_configs
        ),
        train_environment_configs=EnvironmentConfigs(**train_environment_configs),
        trainer_name=trainer_name,
    )

    sacred_logger = SacredLogger()
    sacred_logger.start()

    experiment_analysis = tune.run(
        checkpoint_freq=1,
        config=trainer_config,
        metric=result_metric_key,
        mode="max",
        run_or_experiment=trainer,
        stop={"training_iteration": hyper_params["num_iterations"]},
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
