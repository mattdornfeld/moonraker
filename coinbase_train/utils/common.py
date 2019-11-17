"""Summary

Attributes:
    Number (typing.TypeVar): Description
"""
from decimal import Decimal
from fractions import Fraction
from functools import reduce
from operator import mul
import random
from statistics import stdev as base_stdev
from typing import Generator, Iterable, List, Sequence, TypeVar

import numpy as np
import tensorflow as tf

_Number = TypeVar("_Number", float, Decimal, Fraction)


def all_but_last(iterable: Iterable) -> Generator:
    """
    all_but_last [summary]

    Args:
        iterable (Iterable): [description]

    Returns:
        Generator: [description]
    """
    iterator = iter(iterable)
    current = iterator.__next__()
    for i in iterator:
        yield current
        current = i


def prod(factors: Sequence[float]) -> float:
    """
    prod [summary]

    Args:
        factors (Sequence[float]): [description]

    Returns:
        float: [description]
    """
    return reduce(mul, factors, 1)


def set_seed(seed: int) -> None:
    """
    set_seed [summary]

    Args:
        seed (int): [description]

    Returns:
        None: [description]
    """
    np.random.seed(seed)
    random.seed(seed)
    tf.set_random_seed(seed)


def stdev(data: List[_Number]) -> _Number:
    """Basically statistics.stdev but does not throw an
    error for list of length 1. For lists of length 1 will
    return 0. Otherwise returns statistics.stdev of list.

    Args:
        data (List[Number]): Description

    Returns:
        _Number: stdev
    """
    _data = data.__mul__(2) if len(data) == 1 else data

    return base_stdev(_data)
