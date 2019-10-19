"""
 [summary]
"""
from math import inf
from typing import NamedTuple, Optional, Tuple

import numpy as np
from gym.spaces import Box
from gym.spaces import Tuple as TupleSpace


class ObservationSpaceShape(NamedTuple):
    """
    ObservationSpaceShape [summary]
    """

    account_funds: Tuple
    order_book: Tuple
    time_series: Tuple


class Observation(NamedTuple):
    """
     [summary]
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
        self.account_funds_space = Box(low=0, high=inf, shape=shape.account_funds)
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
