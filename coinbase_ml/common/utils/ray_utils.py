"""
 [summary]
"""
import json
from functools import singledispatch
from statistics import mean
from typing import Any, DefaultDict, Dict, List

import numpy as np
import requests
from ray.rllib.agents import Trainer
from ray.rllib.agents.callbacks import DefaultCallbacks
from ray.rllib.env import BaseEnv
from ray.rllib.evaluation import MultiAgentEpisode, RolloutWorker
from ray.rllib.policy import Policy
from ray.rllib.models import ModelCatalog
from ray.rllib.models.tf.tf_modelv2 import TFModelV2

import coinbase_ml.common.constants as c


EVALUATION_EPISODES: List[MultiAgentEpisode] = []


@singledispatch
def _to_serializable(val: Any) -> str:
    """Used by default."""
    return str(val)


@_to_serializable.register(np.float32)
def _ts_float32(val: Any) -> np.float64:
    """Used if *val* is an instance of numpy.float32."""
    return np.float64(val)


# pylint: disable=unused-argument
class Callbacks(DefaultCallbacks):
    """Callback methods run by RLLib jobs
    """

    @staticmethod
    def _construct_evaluation_custom_metrics() -> Dict[str, float]:
        episode_values: DefaultDict[str, List[float]] = DefaultDict(list)
        for episode in EVALUATION_EPISODES:
            # pylint: disable=invalid-sequence-index
            episode_values[
                "episode_reward"
            ].append(
                episode.total_reward
            )
            # pylint: enable=invalid-sequence-index
            for key, value in episode.last_info_for().items():
                episode_values[key].append(value)

        # pylint: disable=no-member
        episode_values_mean = {f"{k}_mean": mean(v) for k, v in episode_values.items()}
        episode_values_min = {f"{k}_min": min(v) for k, v in episode_values.items()}
        episode_values_max = {f"{k}_max": min(v) for k, v in episode_values.items()}
        # pylint: enable=no-member

        return {**episode_values_mean, **episode_values_max, **episode_values_min}

    def on_episode_end(
        self,
        *,
        worker: RolloutWorker,
        base_env: BaseEnv,
        policies: Dict[str, Policy],
        episode: MultiAgentEpisode,
        env_index: int,
        **kwargs: dict,
    ) -> None:
        """Add relevant values to the custom_metrics dict
        """
        env = base_env.get_unwrapped()[0]
        if env.is_test_environment:
            EVALUATION_EPISODES.append(episode)

        episode.custom_metrics.update(episode.last_info_for())
        episode.custom_metrics["episode_reward"] = episode.total_reward

    def on_train_result(
        self, *, trainer: Trainer, result: dict, **kwargs: dict
    ) -> None:
        """Send result dict to the SACRED_LOGGER server on the driver, which will
        log relevant values to Sacred
        """
        result[
            "evaluation_custom_metrics"
        ] = self._construct_evaluation_custom_metrics()
        EVALUATION_EPISODES.clear()
        response = requests.post(
            url=f"http://0.0.0.0:{c.SACRED_LOGGER_PORT}",
            json=json.dumps(result, default=_to_serializable),
        )
        response.raise_for_status()


# pylint: enable=unused-argument


def register_custom_models(custom_models: List[TFModelV2]) -> None:
    """
    register_custom_models [summary]

    Args:
        custom_models (List[TFModelV2]): [description]
    """
    for i, model in enumerate(custom_models):
        ModelCatalog.register_custom_model(f"CustomModel{i}", model)
