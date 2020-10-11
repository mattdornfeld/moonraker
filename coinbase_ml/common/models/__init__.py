"""
 [summary]
"""
from typing import Dict, Type

from ray.rllib.models.tf.tf_modelv2 import TFModelV2

from coinbase_ml.common.models.ppo_actor_value import PPOActorValue
from coinbase_ml.common.models.td3_actor_critic import TD3ActorCritic

MODELS: Dict[str, Type[TFModelV2]] = {
    "ActorValueModel": PPOActorValue,
    "TD3ActorCritic": TD3ActorCritic,
}
