"""
 [summary]
"""
from math import sqrt
from typing import Callable, Generic, List, Sequence, TypeVar

import numpy as np

T = TypeVar("T")


class NormalizedOperation(Generic[T]):

    """A class for the normalizing the output of any operator that outputs a float.
    Normalization is done using the z-normalization based on a running mean and variance.and
    Useful for reinforcement learning algorithms.
    """

    def __init__(
        self,
        operator: Callable[[T], float],
        name: str,
        normalize: bool = True,
        update: bool = True,
    ):
        """Summary

        Args:
            operator (Callable[[T], float]): This operator will be executed when
            name (str): Description
            normalize (bool optional): The result of __call__ is Normalized when
            __call__ is called. If normalize is True the output will be normalized.
            using running mean and variance if True. If False __call__ will
            simply apply operator.
            update (bool): If True will update running mean and variance on each
            __call__.
        """
        self._mean = 0.0
        self._m = 0.0
        self._num_samples = 0
        self._operator: Callable[[T], float] = operator
        self._s = 0.0
        self._variance = 0.0
        self.name = name
        self.normalize = normalize
        self.update = update

    def __call__(self, operand: T) -> float:
        """Summary

        Args:
            operand (T): Description

        Returns:
            float: Description
        """
        _result = self._operator(operand)

        if self.normalize:
            if self.update:
                self._num_samples += 1
                self._update_mean(_result)
                self._update_variance(_result)

            result = (_result - self._mean) / (sqrt(self._variance) + 1e-12)
        else:
            result = _result

        return result

    def __repr__(self) -> str:
        """Summary

        Returns:
            str: Description
        """
        return f"<Normalized {self.name}>"

    def _update_mean(self, result: float) -> None:
        """Summary

        Args:
            result (float): Description
        """
        self._mean = (result + self._num_samples * self._mean) / (self._num_samples + 1)

    def _update_variance(self, result: float) -> None:
        """Summary

        Args:
            result (float): Description
        """
        if self._num_samples == 1:
            self._m = result
            self._variance = 0
        else:
            old_m = self._m
            self._m = old_m + (result - old_m) / self._num_samples

            old_s = self._s
            self._s = old_s + (result - old_m) * (result - self._m)

            self._variance = self._s / (self._num_samples - 1)


def clamp_to_range(num: float, smallest: float, largest: float) -> float:
    """
    clamp_to_range [summary]

    Args:
        num (float): [description]
        smallest (float): [description]
        largest (float): [description]

    Returns:
        float: [description]
    """
    return max(smallest, min(num, largest))


def convert_to_bool(num: float) -> bool:
    """
    convert_to_bool [summary]

    Args:
        num (float): [description]

    Returns:
        bool: [description]
    """
    return bool(round(clamp_to_range(num, 0, 1)))


def pad_to_length(array: np.ndarray, length: int, pad_value: float = 0.0) -> np.ndarray:
    """Summary

    Args:
        array (np.ndarray): Description
        length (int): Description
        pad_value (float, optional): Description

    Returns:
        np.ndarray: Description
    """
    return np.pad(
        array=array,
        pad_width=((0, length - len(array)), (0, 0)),
        mode="constant",
        constant_values=(pad_value,),
    )


def min_max_normalization(max_value: float, min_value: float, num: float) -> float:
    """Summary

    Args:
        max_value (float): Description
        min_value (float): Description
        num (float): Description

    Returns:
        float: Description
    """
    return (num - min_value) / (max_value - min_value)


def softmax(x: Sequence[float]) -> List[bool]:
    """
    softmax computes softmax values for each sets of scores in x

    Args:
        x (Sequence[float]): [description]

    Returns:
        List[bool]: [description]
    """
    _softmax = np.exp(x) / np.sum(np.exp(x), axis=0)

    return [i == np.argmax(_softmax) for i in range(len(_softmax))]
