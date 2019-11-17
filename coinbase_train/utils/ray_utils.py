"""
 [summary]
"""
from typing import Any, Dict, List

from ray.rllib.evaluation.episode import MultiAgentEpisode
from ray.rllib.models import ModelCatalog
from ray.rllib.models.tf.tf_modelv2 import TFModelV2


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


def register_custom_models(custom_models: List[TFModelV2]) -> None:
    """
    register_custom_models [summary]

    Args:
        custom_models (List[TFModelV2]): [description]
    """
    for i, model in enumerate(custom_models):
        ModelCatalog.register_custom_model(f"CustomModel{i}", model)
