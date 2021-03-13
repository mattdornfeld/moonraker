from typing import TYPE_CHECKING, Dict

from ray.tune.sample import Sampler
from typing_extensions import Protocol

from gym.spaces import Box, Discrete

from coinbase_ml.common.protos.environment_pb2 import Actionizer as ActionizerProto

if TYPE_CHECKING:
    import coinbase_ml.common.protos.environment_pb2 as environment_pb2


class Actionizer(Protocol):
    proto_value: "environment_pb2.ActionizerValue"
    output_dimension: int
    action_space: Box

    @classmethod
    def process_tune_search_config(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> Dict[str, float]:
        """Processes sampled hyper parameters stored in a tune search_config
        with custom logic.
        """


class SignalPositionSize(Actionizer):
    proto_value = ActionizerProto.SignalPositionSize
    output_dimension = 2
    action_space = Box(low=0.0, high=1.0, shape=(output_dimension,))

    @classmethod
    def process_tune_search_config(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> Dict[str, float]:
        pass


class PositionSize(Actionizer):
    proto_value = ActionizerProto.PositionSize
    output_dimension = 1
    action_space = Box(low=0.0, high=1.0, shape=(output_dimension,))

    @classmethod
    def process_tune_search_config(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> Dict[str, float]:
        pass


class EntrySignal(Actionizer):
    proto_value = ActionizerProto.EntrySignal
    output_dimension = 1
    action_space = Discrete(2)

    @classmethod
    def process_tune_search_config(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> Dict[str, float]:
        pass


class EmaCrossover(Actionizer):
    proto_value = ActionizerProto.EmaCrossOver
    output_dimension = 1
    action_space = Discrete(1)

    @classmethod
    def process_tune_search_config(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> Dict[str, float]:
        """Transforms fastWindowSize into a range bounded by slowWindowSize
        """
        assert (
            tune_config["fastWindowSize"].lower <= tune_config["slowWindowSize"].lower
        )
        lower = tune_config["fastWindowSize"].lower
        old_upper = tune_config["fastWindowSize"].upper
        new_upper = search_config["slowWindowSize"]

        m = (new_upper - lower) / (old_upper - lower)
        b = new_upper - m * old_upper

        return {
            "fastWindowSize": m * search_config["fastWindowSize"] + b,
            "slowWindowSize": search_config["slowWindowSize"],
        }


class BollingerOnBookVolume(Actionizer):
    proto_value = ActionizerProto.BollingerOnBookVolume
    output_dimension = 1
    action_space = Discrete(1)

    @classmethod
    def process_tune_search_config(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> Dict[str, float]:
        search_config["onBookVolumeChangeBuyThreshold"] = -search_config[
            "onBookVolumeChangeBuyThreshold"
        ]

        return search_config
