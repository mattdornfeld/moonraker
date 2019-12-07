"""
 [summary]
"""
from dataclasses import dataclass
from math import inf
from typing import Optional, NamedTuple, Tuple

import numpy as np
from gym.spaces import Box
from gym.spaces import Tuple as TupleSpace

from coinbase_ml.common import constants as c


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
    Observation [summary]
    """

    account_funds: np.ndarray
    order_book: np.ndarray
    time_series: np.ndarray


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
