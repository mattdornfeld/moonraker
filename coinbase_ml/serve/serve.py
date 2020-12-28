"""
Downloads artifacts from train run and starts RLLib policy server
"""
from pathlib import Path
from tempfile import TemporaryDirectory, TemporaryFile
from typing import Any, Dict

import ray
import ray.cloudpickle as cloudpickle
from ray.rllib.env.env_context import EnvContext
from ray.tune.registry import register_env

from coinbase_ml.common import constants as cc
from coinbase_ml.common.observations import (
    ActionSpace,
    ObservationSpace,
    ObservationSpaceShape,
)
from coinbase_ml.common.utils.gcs_utils import download_file_from_gcs
from coinbase_ml.common.utils.ray_utils import get_trainer, register_custom_model
from coinbase_ml.common.utils.sacred_utils import get_sacred_experiment_results
from coinbase_ml.serve.environment import Environment
from coinbase_ml.serve.experiment_configs.default import config


def env_creator(env_config: EnvContext) -> Environment:
    """
    env_creator [summary]

    Args:
        env_config (EnvContext): [description]

    Returns:
        Environment: [description]
    """
    num_time_steps = env_config["num_time_steps"]

    observation_space_shape = ObservationSpaceShape(
        account_funds=(1, 4),
        order_book=(num_time_steps, 4 * cc.ORDER_BOOK_DEPTH),
        time_series=(num_time_steps, cc.NUM_CHANNELS_IN_TIME_SERIES),
    )

    environment = Environment(
        action_space=ActionSpace(),
        observation_space=ObservationSpace(observation_space_shape),
    )

    return environment


def serve() -> None:
    """
    serve [summary]
    """
    sacred_experiment = get_sacred_experiment_results(config()["train_experiment_id"])

    model_bucket_name = sacred_experiment.info["model_bucket_name"]
    checkpoint_gcs_key = sacred_experiment.info["checkpoint_gcs_key"]
    checkpoint_metadata_gcs_key = sacred_experiment.info["checkpoint_metadata_gcs_key"]
    trainer_config_gcs_key = sacred_experiment.info["trainer_config_gcs_key"]
    trainer_class = get_trainer(sacred_experiment.config["trainer_name"])
    model_name = sacred_experiment.config["model_name"]

    with TemporaryFile() as file:
        download_file_from_gcs(
            bucket_name=model_bucket_name,
            credentials_path=cc.SERVICE_ACCOUNT_JSON,
            file=file,
            gcp_project_name=cc.GCP_PROJECT_NAME,
            key=trainer_config_gcs_key,
        )

        trainer_config: Dict[str, Any] = cloudpickle.load(file)

    del trainer_config["evaluation_config"]
    del trainer_config["evaluation_num_episodes"]
    del trainer_config["evaluation_interval"]
    trainer_config["num_workers"] = 0

    ray.init()
    register_custom_model(model_name)
    register_env("ServingEnvironment", env_creator)
    trainer = trainer_class(config=trainer_config, env="ServingEnvironment")

    # Restore trainer to saved state
    with TemporaryDirectory() as dir_name:
        checkpoint_filename = Path(dir_name) / "rllib_checkpoint"
        checkpoint_metadata_filename = Path(dir_name) / "rllib_checkpoint.tune_metadata"

        download_file_from_gcs(
            bucket_name=model_bucket_name,
            credentials_path=cc.SERVICE_ACCOUNT_JSON,
            filename=checkpoint_filename,
            gcp_project_name=cc.GCP_PROJECT_NAME,
            key=checkpoint_gcs_key,
        )

        download_file_from_gcs(
            bucket_name=model_bucket_name,
            credentials_path=cc.SERVICE_ACCOUNT_JSON,
            filename=checkpoint_metadata_filename,
            gcp_project_name=cc.GCP_PROJECT_NAME,
            key=checkpoint_metadata_gcs_key,
        )

        trainer.restore(str(checkpoint_filename))

    while True:
        trainer.train()


if __name__ == "__main__":
    serve()
