"""Summary
"""
import io
import logging
from math import isnan
from tempfile import TemporaryDirectory
from typing import Any, Dict, List

from sacred.run import Run

import ray
import ray.cloudpickle as cloudpickle
from ray.rllib.models.tf.tf_modelv2 import TFModelV2

from coinbase_train import constants as c
from coinbase_train import models
from coinbase_train import reward
from coinbase_train.experiment_configs.common import SACRED_EXPERIMENT
from coinbase_train.trainers import get_and_build_trainer
from coinbase_train.utils.config_utils import EnvironmentConfigs, HyperParameters
from coinbase_train.utils.gcs_utils import get_gcs_base_path, upload_file_to_gcs
from coinbase_train.utils.ray_utils import register_custom_models


LOGGER = logging.getLogger(__name__)


def calc_return_value(
    evaluation_metrics: Dict[str, float], return_value_key: str
) -> float:
    """
    calc_return_value [summary]

    Args:
        evaluation_metrics (Dict[str, float]): [description]
        return_value_key (str): [description]

    Returns:
        float: [description]
    """
    if return_value_key == "reward":
        return_value = evaluation_metrics["episode_reward_mean"]
    elif return_value_key == "roi":
        return_value = evaluation_metrics["roi_mean"]
    else:
        raise ValueError

    return return_value


def log_metrics_to_sacred(metrics: Dict[str, float], prefix: str) -> None:
    """
    log_metrics_to_sacred [summary]

    Args:
        metrics (Dict[str, float]): [description]
        prefix (str): [description]

    Returns:
        None: [description]
    """
    for metric_name, metric in metrics.items():
        _metric_name = f"{prefix}_{metric_name}"
        SACRED_EXPERIMENT.log_scalar(_metric_name, 0.0 if isnan(metric) else metric)


@SACRED_EXPERIMENT.automain
def main(
    _run: Run,
    custom_model_names: List[str],
    hyper_params: Dict[str, Any],
    return_value_key: str,
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
        return_value_key (str): [description]
        seed (int): [description]
        test_environment_configs (Dict[str, Any]): [description]
        train_environment_configs (Dict[str, Any]): [description]

    Returns:
        float: [description]
    """
    test_reward_strategy_name = test_environment_configs.pop("reward_strategy_name")
    train_reward_strategy_name = train_environment_configs.pop("reward_strategy_name")
    test_environment_configs["reward_strategy"] = reward.__dict__[
        test_reward_strategy_name
    ]()
    train_environment_configs["reward_strategy"] = reward.__dict__[
        train_reward_strategy_name
    ]()

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
    best_iteration_reward = float("-inf")
    best_results: Dict[str, Any] = {}
    for _ in range(hyper_params["num_iterations"]):
        results: Dict[str, Any] = trainer.train()

        LOGGER.info(results)

        checkpoint: str = trainer.save(checkpoint_dir.name)

        if "best_checkpoint" not in locals():
            best_checkpoint = checkpoint

        if results["evaluation"]["episode_reward_mean"] > best_iteration_reward:
            best_iteration_reward = results["evaluation"]["episode_reward_mean"]
            best_checkpoint = checkpoint
            best_results = results

        log_metrics_to_sacred(results["custom_metrics"], prefix="train")
        log_metrics_to_sacred(
            results["info"]["learner"]["default_policy"], prefix="train"
        )
        log_metrics_to_sacred(results["evaluation"]["custom_metrics"], prefix="test")

    LOGGER.info(best_results)

    checkpoint_gcs_key = f"{get_gcs_base_path(_run)}/rllib_checkpoint"

    # upload best checkpoint
    upload_file_to_gcs(
        bucket_name=c.MODEL_BUCKET_NAME,
        credentials_path=c.SERVICE_ACCOUNT_JSON,
        filename=best_checkpoint,
        gcp_project_name=c.GCP_PROJECT_NAME,
        key=checkpoint_gcs_key,
    )

    # upload best checkpoint metadata
    upload_file_to_gcs(
        bucket_name=c.MODEL_BUCKET_NAME,
        credentials_path=c.SERVICE_ACCOUNT_JSON,
        filename=best_checkpoint + ".tune_metadata",
        gcp_project_name=c.GCP_PROJECT_NAME,
        key=checkpoint_gcs_key + ".tune_metadata",
    )

    # upload trainer config
    with io.BytesIO() as config_pickle_file:
        cloudpickle.dump(trainer.config, config_pickle_file)
        config_pickle_file.seek(0)
        upload_file_to_gcs(
            bucket_name=c.MODEL_BUCKET_NAME,
            credentials_path=c.SERVICE_ACCOUNT_JSON,
            file=config_pickle_file,
            gcp_project_name=c.GCP_PROJECT_NAME,
            key=checkpoint_gcs_key + "_config.pkl",
        )

    checkpoint_dir.cleanup()

    return calc_return_value(
        best_results["evaluation"]["custom_metrics"], return_value_key
    )
