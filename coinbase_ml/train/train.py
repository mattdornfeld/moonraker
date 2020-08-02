"""Summary
"""
import io
import logging
from pprint import pformat
from tempfile import TemporaryDirectory
from typing import Any, Dict, List

from sacred.run import Run

import ray
import ray.cloudpickle as cloudpickle
from ray.rllib.models.tf.tf_modelv2 import TFModelV2

from coinbase_ml.common import constants as cc
from coinbase_ml.common import models
from coinbase_ml.common.reward import REWARD_STRATEGIES
from coinbase_ml.common.utils.gcs_utils import get_gcs_base_path, upload_file_to_gcs
from coinbase_ml.common.utils.ray_utils import register_custom_models
from coinbase_ml.common.utils.sacred_utils import log_metrics_to_sacred
from coinbase_ml.serve.metrics_recorder import Metrics
from coinbase_ml.train import constants as c
from coinbase_ml.train.experiment_configs.common import SACRED_EXPERIMENT
from coinbase_ml.train.trainers import get_and_build_trainer
from coinbase_ml.train.utils.config_utils import EnvironmentConfigs, HyperParameters


LOGGER = logging.getLogger(__name__)


@SACRED_EXPERIMENT.main
def main(
    _run: Run,
    custom_model_names: List[str],
    hyper_params: Dict[str, Any],
    result_metric: Metrics,
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
        result_metric (Metrics): Metric recorded as result in Sacred.
            Note that what's actually recorded is the mean of that metric,
            averaged over all episodes in an iteration.
        seed (int): [description]
        test_environment_configs (Dict[str, Any]): [description]
        train_environment_configs (Dict[str, Any]): [description]

    Returns:
        float: [description]
    """
    result_metric_key = f"{result_metric.value}_mean"

    for configs in [test_environment_configs, train_environment_configs]:
        reward_strategy_name: str = configs.pop("reward_strategy_name")
        configs["reward_strategy"] = REWARD_STRATEGIES[reward_strategy_name]
        configs["initial_usd"] = cc.PRODUCT_ID.quote_volume_type(configs["initial_usd"])
        configs["initial_btc"] = cc.PRODUCT_ID.quote_volume_type(configs["initial_btc"])

    ray.init(
        include_webui=c.RAY_INCLUDE_WEBUI,
        object_store_memory=c.RAY_OBJECT_STORE_MEMORY,
        redis_address=c.RAY_REDIS_ADDRESS,
    )

    custom_models: List[TFModelV2] = [
        models.__dict__[model_name] for model_name in custom_model_names
    ]
    register_custom_models(custom_models)

    trainer = get_and_build_trainer(
        hyper_params=HyperParameters(**hyper_params),
        test_environment_configs=EnvironmentConfigs(
            is_test_environment=True, **test_environment_configs
        ),
        train_environment_configs=EnvironmentConfigs(**train_environment_configs),
        trainer_name=trainer_name,
    )

    checkpoint_dir = TemporaryDirectory()
    best_iteration_result = float("-inf")
    best_results: Dict[str, Any] = {}

    for iteration in range(hyper_params["num_iterations"]):
        results: Dict[str, Any] = trainer.train()

        LOGGER.info("iteration: %s", iteration)
        LOGGER.info("train_metrics: %s", pformat(results["custom_metrics"]))
        LOGGER.info("train_performance: %s", pformat(results["sampler_perf"]))
        LOGGER.info(
            "eval_metrics: %s", pformat(results["evaluation"]["custom_metrics"])
        )
        LOGGER.info(
            "eval_performance: %s", pformat(results["evaluation"]["sampler_perf"])
        )

        checkpoint: str = trainer.save(checkpoint_dir.name)

        if "best_checkpoint" not in locals():
            best_checkpoint = checkpoint

        if (
            results["evaluation"]["custom_metrics"][result_metric_key]
            > best_iteration_result
        ):
            best_iteration_result = results["evaluation"]["custom_metrics"][
                result_metric_key
            ]
            best_checkpoint = checkpoint
            best_results = results

        log_metrics_to_sacred(
            SACRED_EXPERIMENT, results["custom_metrics"], prefix="train"
        )
        log_metrics_to_sacred(
            SACRED_EXPERIMENT,
            results["info"]["learner"]["default_policy"],
            prefix="train",
        )
        log_metrics_to_sacred(
            SACRED_EXPERIMENT, results["evaluation"]["custom_metrics"], prefix="test"
        )

    LOGGER.info(
        "best_eval_metrics: %s", pformat(best_results["evaluation"]["custom_metrics"])
    )

    # upload best checkpoint
    checkpoint_key = f"{get_gcs_base_path(_run)}/rllib_checkpoint"
    upload_file_to_gcs(
        bucket_name=cc.MODEL_BUCKET_NAME,
        credentials_path=cc.SERVICE_ACCOUNT_JSON,
        filename=best_checkpoint,
        gcp_project_name=cc.GCP_PROJECT_NAME,
        key=checkpoint_key,
    )

    # upload best checkpoint metadata
    checkpoint_metadata_key = (
        f"{get_gcs_base_path(_run)}/rllib_checkpoint.tune_metadata"
    )
    upload_file_to_gcs(
        bucket_name=cc.MODEL_BUCKET_NAME,
        credentials_path=cc.SERVICE_ACCOUNT_JSON,
        filename=best_checkpoint + ".tune_metadata",
        gcp_project_name=cc.GCP_PROJECT_NAME,
        key=checkpoint_metadata_key,
    )

    # upload trainer config
    trainer_config_key = f"{get_gcs_base_path(_run)}/trainer_config.pkl"
    with io.BytesIO() as config_pickle_file:
        cloudpickle.dump(trainer.config, config_pickle_file)
        config_pickle_file.seek(0)
        upload_file_to_gcs(
            bucket_name=cc.MODEL_BUCKET_NAME,
            credentials_path=cc.SERVICE_ACCOUNT_JSON,
            file=config_pickle_file,
            gcp_project_name=cc.GCP_PROJECT_NAME,
            key=trainer_config_key,
        )

    checkpoint_dir.cleanup()

    _run.info["model_bucket_name"] = cc.MODEL_BUCKET_NAME
    _run.info["checkpoint_gcs_key"] = checkpoint_key
    _run.info["checkpoint_metadata_gcs_key"] = checkpoint_metadata_key
    _run.info["trainer_config_gcs_key"] = trainer_config_key

    return float(best_results["evaluation"]["custom_metrics"][result_metric_key])


if __name__ == "__main__":
    SACRED_EXPERIMENT.run_commandline()
