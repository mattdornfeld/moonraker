"""
 [summary]
"""
from typing import Any, Dict, List

from ray.rllib.agents.callbacks import DefaultCallbacks
from ray.rllib.env import BaseEnv
from ray.rllib.evaluation import MultiAgentEpisode, RolloutWorker
from ray.rllib.policy import Policy
from ray.rllib.models import ModelCatalog
from ray.rllib.models.tf.tf_modelv2 import TFModelV2


# pylint: disable=unused-argument
class Callbacks(DefaultCallbacks):
    """Callback methods run by RLLib jobs
    """

    def on_episode_end(
        self,
        *,
        worker: RolloutWorker,
        base_env: BaseEnv,
        policies: Dict[str, Policy],
        episode: MultiAgentEpisode,
        env_index: int,
        **kwargs: Dict[Any, Any],
    ) -> None:
        """
        on_episode_end is run at the end of each episode.
        It accepts as an argument a dict that contains info
        about the episode run.

        Args:
            info (Dict[str, Any]): [description]

        Returns:
            None: [description]
        """
        episode.custom_metrics.update(episode.last_info_for())
        episode.custom_metrics["episode_reward"] = episode.total_reward


# pylint: enable=unused-argument


def register_custom_models(custom_models: List[TFModelV2]) -> None:
    """
    register_custom_models [summary]

    Args:
        custom_models (List[TFModelV2]): [description]
    """
    for i, model in enumerate(custom_models):
        ModelCatalog.register_custom_model(f"CustomModel{i}", model)
