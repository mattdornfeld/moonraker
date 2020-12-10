"""
 [summary]
"""
import json
from functools import singledispatch
from statistics import mean
from time import time
from typing import Any, Callable, DefaultDict, Dict, List, Optional

import numpy as np
import requests
from nptyping import NDArray
from ray.rllib.agents import Trainer
from ray.rllib.agents.callbacks import DefaultCallbacks
from ray.rllib.env import BaseEnv
from ray.rllib.evaluation import MultiAgentEpisode, RolloutWorker
from ray.rllib.policy import Policy
from ray.rllib.models import ModelCatalog
from ray.rllib.models.tf.tf_modelv2 import TFModelV2
from ray.rllib.utils.typing import PolicyID

import coinbase_ml.common.constants as c
from coinbase_ml.common.protos.environment_pb2 import InfoDictKey
from coinbase_ml.fakebase.protos import fakebase_pb2
from coinbase_ml.train.environment import Environment


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

    _bins_lookup = {
        0: np.array([0.0, 0.333, 0.667, 1.0]),
        1: np.arange(0.0, 1.1, 0.1),
    }

    def __init__(self, legacy_callbacks_dict: Dict[str, Callable] = None):
        super().__init__(legacy_callbacks_dict)
        self._actions: List[NDArray[float]] = []
        self._episode_start_time: Optional[float] = None

    @staticmethod
    def _construct_evaluation_custom_metrics() -> Dict[str, float]:
        episode_values: DefaultDict[str, List[float]] = DefaultDict(list)
        for episode in EVALUATION_EPISODES:
            # pylint: disable=invalid-sequence-index
            episode_values["episode_reward"].append(episode.total_reward)
            # pylint: enable=invalid-sequence-index
            for key, value in episode.last_info_for().items():
                episode_values[key].append(value)

        # pylint: disable=no-member
        episode_values_mean = {f"{k}_mean": mean(v) for k, v in episode_values.items()}
        episode_values_min = {f"{k}_min": min(v) for k, v in episode_values.items()}
        episode_values_max = {f"{k}_max": min(v) for k, v in episode_values.items()}
        # pylint: enable=no-member

        return {**episode_values_mean, **episode_values_max, **episode_values_min}

    def _format_action_histogram(self) -> str:
        action_dim = self._actions[0].shape[0]
        action_histogram = "\n"
        for i in range(action_dim):
            hist = np.histogram(
                np.array(self._actions)[:, i], bins=self._bins_lookup[i]
            )[0]
            action_histogram += f"\t\t{i}: {hist}\n"

        return action_histogram

    @staticmethod
    def _print_episode_metrics(
        info_dict: Dict[str, float],
        action_histogram: str,
        episode_number: int,
        episode_reward: float,
        worker_index: int,
        simulation_duration: float,
        simulation_id: str,
        simulation_type: "fakebase_pb2.SimulationTypeValue",
    ) -> None:
        _simulation_type = fakebase_pb2.SimulationType.Name(simulation_type)
        avg_simulation_step_duration = info_dict[
            InfoDictKey.Name(InfoDictKey.simulationStepDuration)
        ]
        print(
            f"{_simulation_type} simulation {simulation_id} episode {episode_number} summary:\n"
            f"\tworkerIndex={worker_index}\n"
            f"\tepisodeReward = {episode_reward}\n"
            f"\tsimulationDuration = {simulation_duration} s\n"
            f"\tavgSimulationStepDuration = {avg_simulation_step_duration} ms\n"
            f"\tendPortfolioValue = {info_dict[InfoDictKey.Name(InfoDictKey.portfolioValue)]}\n"
            f"\tbuyVolumeTraded = {info_dict[InfoDictKey.Name(InfoDictKey.buyVolumeTraded)]}\n"
            f"\tbuyFeesPaid = {info_dict[InfoDictKey.Name(InfoDictKey.buyFeesPaid)]}\n"
            f"\tsellVolumeTraded = {info_dict[InfoDictKey.Name(InfoDictKey.sellVolumeTraded)]}\n"
            f"\tsellFeesPaid = {info_dict[InfoDictKey.Name(InfoDictKey.sellFeesPaid)]}\n"
            f"\tactionHistogram = {action_histogram}"
        )

    def on_episode_start(
        self,
        *,
        worker: RolloutWorker,
        base_env: BaseEnv,
        policies: Dict[PolicyID, Policy],
        episode: MultiAgentEpisode,
        env_index: Optional[int] = None,
        **kwargs: dict,
    ) -> None:
        self._actions = []
        self._episode_start_time = time()

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
        """Add relevant values to the custom_metrics dict and print episode metrics
        """
        env: Environment = base_env.get_unwrapped()[0]
        self._print_episode_metrics(
            episode.last_info_for(),
            self._format_action_histogram(),
            env.episode_number,
            episode.total_reward,
            env.worker_index,
            time() - self._episode_start_time,
            env.exchange.simulation_id,
            env.config.simulation_type,
        )
        if env.is_test_environment:
            EVALUATION_EPISODES.append(episode)

        episode.custom_metrics.update(episode.last_info_for())
        episode.custom_metrics["episode_reward"] = episode.total_reward

    def on_episode_step(
        self,
        *,
        worker: RolloutWorker,
        base_env: BaseEnv,
        episode: MultiAgentEpisode,
        env_index: int,
        **kwargs: dict,
    ) -> None:
        """Record step action
        """
        self._actions.append(episode.last_action_for())

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
