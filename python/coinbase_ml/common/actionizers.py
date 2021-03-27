from typing import Dict

from ray.tune.sample import Sampler
from typing_extensions import Protocol

from gym.spaces import Box, Discrete

from coinbase_ml.common.protos import actionizers_pb2 as apb
from coinbase_ml.common.protos.actionizers_pb2 import Actionizer as ActionizerProto
from coinbase_ml.common.protos import indicators_pb2 as ipb


class Actionizer(Protocol):
    proto_value: "apb.ActionizerValue"
    output_dimension: int
    action_space: Box

    @classmethod
    def build_actionizer_configs(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> apb.ActionizerConfigs:
        """Generates an ActionizerConfigs object from a search_config and tune_config
        """


class SignalPositionSize(Actionizer):
    proto_value = ActionizerProto.SignalPositionSize
    output_dimension = 2
    action_space = Box(low=0.0, high=1.0, shape=(output_dimension,))

    @classmethod
    def build_actionizer_configs(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> apb.ActionizerConfigs:
        return apb.ActionizerConfigs(signalPositionSize=apb.SignalPositionSizeConfigs())


class PositionSize(Actionizer):
    proto_value = ActionizerProto.PositionSize
    output_dimension = 1
    action_space = Box(low=0.0, high=1.0, shape=(output_dimension,))

    @classmethod
    def build_actionizer_configs(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> apb.ActionizerConfigs:
        return apb.ActionizerConfigs(positionSize=apb.PositionSizeConfigs())


class EntrySignal(Actionizer):
    proto_value = ActionizerProto.EntrySignal
    output_dimension = 1
    action_space = Discrete(2)

    @classmethod
    def build_actionizer_configs(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> apb.ActionizerConfigs:
        return apb.ActionizerConfigs(entrySignal=apb.EntrySignalConfigs())


class EmaCrossover(Actionizer):
    proto_value = ActionizerProto.EmaCrossOver
    output_dimension = 1
    action_space = Discrete(1)

    @classmethod
    def build_actionizer_configs(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> apb.ActionizerConfigs:
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

        actionizer_configs = apb.EmaCrossOverConfigs(
            emaFast=ipb.ExponentialMovingAverageConfigs(
                windowSize=int(m * search_config["fastWindowSize"] + b)
            ),
            emaSlow=ipb.ExponentialMovingAverageConfigs(
                windowSize=int(search_config["slowWindowSize"])
            ),
        )

        return apb.ActionizerConfigs(emaCrossOver=actionizer_configs)


class BollingerOnBookVolume(Actionizer):
    proto_value = ActionizerProto.BollingerOnBookVolume
    output_dimension = 1
    action_space = Discrete(1)

    @classmethod
    def build_actionizer_configs(
        cls, search_config: Dict[str, float], tune_config: Dict[str, Sampler]
    ) -> apb.ActionizerConfigs:
        search_config["onBookVolumeChangeBuyThreshold"] = -search_config[
            "onBookVolumeChangeBuyThreshold"
        ]

        actionizer_configs = apb.BollingerOnBookVolumeConfigs(
            smoothedOnBookVolume=ipb.KaufmanAdaptiveMovingAverageConfigs(
                windowSize=int(search_config["onBookVolumeWindowSize"])
            ),
            priceMovingVariance=ipb.KaufmanAdaptiveMovingVarianceConfigs(
                movingAverageConfigs=ipb.KaufmanAdaptiveMovingAverageConfigs(
                    windowSize=int(search_config["bollingerBandWindowSize"])
                )
            ),
            sampledOnBookVolumeDerivative=ipb.SampleValueByBarIncrementConfigs(
                barSize=int(search_config["volumeBarSize"])
            ),
            bollingerBandSize=search_config["bollingerBandSize"],
            onBookVolumeChangeBuyThreshold=search_config[
                "onBookVolumeChangeBuyThreshold"
            ],
            onBookVolumeChangeSellThreshold=search_config[
                "onBookVolumeChangeSellThreshold"
            ],
        )

        return apb.ActionizerConfigs(bollingerOnBookVolume=actionizer_configs)
