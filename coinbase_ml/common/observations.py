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
            np.expand_dims(np.array(observation_proto.features.account), 0),
            np.expand_dims(np.array(observation_proto.features.orderBook), 0),
            np.expand_dims(np.array(observation_proto.features.timeSeries), 0),
        )

    @staticmethod
    def from_arrow_sockets(simulation_id: str) -> Observation:
        """Reconstructs the latest observation for the current simulation from an Arrow socket
        """
        arrow_sockets_dir = c.ARROW_SOCKETS_BASE_DIR / simulation_id

        account_funds = read_from_arrow_socket(arrow_sockets_dir / "account.socket")[0]
        order_book = read_from_arrow_socket(arrow_sockets_dir / "orderBook.socket")[0]
        time_series = read_from_arrow_socket(arrow_sockets_dir / "timeSeries.socket")[0]

        return Observation(
            account_funds.to_numpy().transpose(),
            order_book.to_numpy().transpose(),
            time_series.to_numpy().transpose(),
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
