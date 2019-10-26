"""Summary
"""
import io
import logging
import random
from math import isnan
from tempfile import TemporaryDirectory
from typing import Any, Dict

import numpy as np
import tensorflow as tf
from tensorflow.keras import backend as K  # pylint: disable=E0401
from sacred.run import Run

import ray
from ray import tune
from ray.rllib.agents import ppo
from ray.rllib.evaluation.episode import MultiAgentEpisode
from ray.rllib.models import ModelCatalog
from ray.rllib.policy.tf_policy import TFPolicy

from coinbase_train import constants as c
from coinbase_train import reward, utils
from coinbase_train.environment import Environment
from coinbase_train.experiment import SACRED_EXPERIMENT
from coinbase_train.model import ActorCriticModel

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


def set_seed(seed: int) -> None:
    """
    set_seed [summary]

    Args:
        seed (int): [description]

    Returns:
        None: [description]
    """
    np.random.seed(seed)
    random.seed(seed)
    tf.set_random_seed(seed)


def on_episode_end(info: Dict[str, Any]) -> None:
    """
    on_episode_end [summary]

    Args:
        info (Dict[str, Any]): [description]

    Returns:
        None: [description]
    """
    episode: MultiAgentEpisode = info["episode"]
    episode.custom_metrics.update(episode.last_info_for())
    episode.custom_metrics["episode_reward"] = episode.total_reward


@SACRED_EXPERIMENT.automain
def main(
    _run: Run,
    hyper_params: dict,
    return_value_key: str,
    seed: int,
    test_environment_configs: dict,
    train_environment_configs: dict,
) -> float:
    """
    main builds an agent, trains on the train environment, evaluates on the test
    environment, saves artifacts to gcs. Logs results to Sacred.

    Args:
        _run (Run): [description]
        hyper_params (dict): [description]
        return_value_key (str): [description]
        seed (int): [description]
        test_environment_configs (dict): [description]
        train_environment_configs (dict): [description]

    Returns:
        float: [description]
    """
    set_seed(seed)

    test_reward_strategy_name = test_environment_configs.pop("reward_strategy_name")
    train_reward_strategy_name = train_environment_configs.pop("reward_strategy_name")
    test_environment_configs["reward_strategy"] = reward.__dict__[
        test_reward_strategy_name
    ]()
    train_environment_configs["reward_strategy"] = reward.__dict__[
        train_reward_strategy_name
    ]()

    _hyper_params = utils.HyperParameters(**hyper_params)
    _train_environment_configs = utils.EnvironmentConfigs(**train_environment_configs)
    _test_environment_configs = utils.EnvironmentConfigs(
        is_test_environment=True, **test_environment_configs
    )

    max_train_episode_steps = utils.calc_nb_max_episode_steps(
        end_dt=_train_environment_configs.end_dt,
        num_warmup_time_steps=_train_environment_configs.num_warmup_time_steps,
        start_dt=_train_environment_configs.start_dt,
        time_delta=_train_environment_configs.time_delta,
    )

    max_test_episode_steps = utils.calc_nb_max_episode_steps(
        end_dt=_test_environment_configs.end_dt,
        num_warmup_time_steps=_test_environment_configs.num_warmup_time_steps,
        start_dt=_test_environment_configs.start_dt,
        time_delta=_test_environment_configs.time_delta,
    )

    ray.init(
        object_store_memory=c.OBJECT_STORE_MEMORY,
        num_cpus=c.NUM_CPUS,
        num_gpus=c.NUM_GPUS,
        redis_address=c.REDIS_ADDRESS,
    )

    ModelCatalog.register_custom_model("ActorCriticModel", ActorCriticModel)
    trainer = ppo.PPOTrainer(
        env=Environment,
        config={
            "callbacks": {"on_episode_end": tune.function(on_episode_end)},
            "env_config": _train_environment_configs,
            "evaluation_config": {
                "env_config": _test_environment_configs,
                "sample_batch_size": max_test_episode_steps,
            },
            "evaluation_interval": 1,
            "evaluation_num_episodes": 1,
            "grad_clip": _hyper_params.gradient_clip,
            "log_level": "INFO",
            "model": {
                "custom_options": {"hyper_params": _hyper_params},
                "custom_model": "ActorCriticModel",
            },
            "num_gpus": c.NUM_GPUS,
            "num_workers": _hyper_params.num_actors,
            "num_sgd_iter": _hyper_params.num_epochs_per_iteration,
            "vf_share_layers": True,
            "train_batch_size": _train_environment_configs.num_episodes
            * max_train_episode_steps,
            "sample_batch_size": max_train_episode_steps,
            "sgd_minibatch_size": _hyper_params.batch_size,
        },
    )

    checkpoint_dir = TemporaryDirectory()
    best_iteration_reward = float("-inf")
    best_results: Dict[str, Any] = {}
    for _ in range(_hyper_params.num_iterations):
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

    checkpoint_gcs_key = f"{utils.get_gcs_base_path(_run)}/rllib_checkpoint"

    # upload best checkpoint
    utils.upload_file_to_gcs(
        bucket_name=c.MODEL_BUCKET_NAME,
        credentials_path=c.SERVICE_ACCOUNT_JSON,
        filename=best_checkpoint,
        gcp_project_name=c.GCP_PROJECT_NAME,
        key=checkpoint_gcs_key,
    )

    # upload best checkpoint metadata
    utils.upload_file_to_gcs(
        bucket_name=c.MODEL_BUCKET_NAME,
        credentials_path=c.SERVICE_ACCOUNT_JSON,
        filename=best_checkpoint + ".tune_metadata",
        gcp_project_name=c.GCP_PROJECT_NAME,
        key=checkpoint_gcs_key + ".tune_metadata",
    )

    trainer.restore(best_checkpoint)
    policy: TFPolicy = trainer.get_policy()
    model: ActorCriticModel = policy.model
    with io.BytesIO() as keras_model_file:
        K.set_session(policy._sess)  # pylint: disable=W0212
        model.actor.save(keras_model_file)
        keras_model_file.seek(0)
        actor_gcs_key = f"{utils.get_gcs_base_path(_run)}/actor.hdf5"
        utils.upload_file_to_gcs(
            bucket_name=c.MODEL_BUCKET_NAME,
            credentials_path=c.SERVICE_ACCOUNT_JSON,
            file=keras_model_file,
            gcp_project_name=c.GCP_PROJECT_NAME,
            key=actor_gcs_key,
        )

    checkpoint_dir.cleanup()

    return calc_return_value(
        best_results["evaluation"]["custom_metrics"], return_value_key
    )
