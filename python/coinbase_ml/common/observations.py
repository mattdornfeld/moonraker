"""
 [summary]
"""
from __future__ import annotations

from dataclasses import dataclass
from math import inf
from typing import Optional, NamedTuple, Tuple

import numpy as np
from gym.spaces import Box
from gym.spaces import Tuple as TupleSpace

import coinbase_ml.common.types
from coinbase_ml.common import constants as c
from coinbase_ml.common.protos.environment_pb2 import Observation as ObservationProto
from coinbase_ml.common.utils.arrow_utils import read_from_arrow_socket


class ActionSpace(Box):
    """
    ActionSpace [summary]
    """

    def __init__(self) -> None:
        super().__init__(low=0.0, high=1.0, shape=(c.ACTOR_OUTPUT_DIMENSION,))


@dataclass
class ObservationSpaceShape:
    """
    ObservationSpaceShape [summary]
    """

    account_funds: Tuple[int, int]
    order_book: Tuple[int, int]
    time_series: Tuple[int, int]


class Observation(NamedTuple):
    """
    A tuple of features that can be fed into a machine learning model
    """

    account_funds: np.ndarray
    order_book: np.ndarray
    time_series: np.ndarray

    @staticmethod
    def from_proto(observation_proto: ObservationProto) -> Observation:
        """Converts an ObservationProto to an Observation
        """
        return Observation(
            np.expand_dims(
                np.array(observation_proto.features.features["account"].feature), 0
            ),
            np.expand_dims(
                np.array(observation_proto.features.features["orderBook"].feature), 0
            ),
            np.expand_dims(
                np.array(observation_proto.features.features["timeSeries"].feature), 0
            ),
        )

    @staticmethod
    def from_arrow_socket(
        simulation_id: coinbase_ml.common.types.SimulationId,
    ) -> Observation:
        """Reconstructs the latest observation for the current simulation from an Arrow socket
        """
        arrow_socket_file = c.ARROW_SOCKETS_BASE_DIR / f"{simulation_id}.socket"
        features = read_from_arrow_socket(arrow_socket_file)[0]

        return Observation(
            np.expand_dims(features.account.dropna().to_numpy(), 0),
            np.expand_dims(features.orderBook.dropna().to_numpy(), 0),
            np.expand_dims(features.timeSeries.dropna().to_numpy(), 0),
        )


class ObservationSpace(TupleSpace):
    """
    ObservationSpace [summary]
    """

    def __init__(self, shape: ObservationSpaceShape) -> None:
        self._flattened_space = None
        self.account_funds_space = Box(low=-inf, high=inf, shape=shape.account_funds)
        self.order_book_space = Box(low=0, high=inf, shape=shape.order_book)
        self.time_series_space = Box(low=-inf, high=inf, shape=shape.time_series)
        super().__init__(
            (self.account_funds_space, self.order_book_space, self.time_series_space)
        )

    def contains(self, x: Observation) -> bool:
        """
        contains [summary]

        Args:
            x (Observation): [description]

        Returns:
            bool: [description]
        """
        return super().contains((x.account_funds, x.order_book, x.time_series))

    @property
    def flattened_space(self) -> Optional[Box]:
        """
        flattened_space [summary]

        Returns:
            Optional[Box]: [description]
        """
        return self._flattened_space

    @flattened_space.setter
    def flattened_space(self, value: Box) -> None:
        """
        flattened_space [summary]

        Args:
            value (Box): [description]

        Returns:
            None: [description]
        """
        self._flattened_space = value

    def sample(self) -> Observation:
        """
        sample [summary]

        Returns:
            Observation: [description]
        """
        return Observation(
            account_funds=self.account_funds_space.sample(),
            order_book=self.order_book_space.sample(),
            time_series=self.time_series_space.sample(),
        )
