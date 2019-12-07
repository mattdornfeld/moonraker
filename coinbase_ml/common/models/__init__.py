"""
 [summary]
"""
from typing import Dict, Type

from ray.rllib.models.tf.tf_modelv2 import TFModelV2

from coinbase_ml.common.models.actor_value import ActorValueModel

MODELS: Dict[str, Type[TFModelV2]] = {"ActorValueModel": ActorValueModel}
