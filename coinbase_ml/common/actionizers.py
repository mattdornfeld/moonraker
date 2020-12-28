"""
 [summary]
"""
from typing import TYPE_CHECKING
from typing_extensions import Protocol

from gym.spaces import Box

from coinbase_ml.common.protos.environment_pb2 import Actionizer as ActionizerProto

if TYPE_CHECKING:
    import coinbase_ml.common.protos.environment_pb2 as environment_pb2


class Actionizer(Protocol):
    value: "environment_pb2.ActionizerValue"
    output_dimension: int
    action_space: Box


class PositionSize(Actionizer):
    value = ActionizerProto.PositionSize
    output_dimension = 1
    action_space = Box(low=0.0, high=1.0, shape=(output_dimension,))
