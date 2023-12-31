"""
utils.py
"""
import random
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
from enum import Enum
from fractions import Fraction
from functools import reduce
from math import log
from operator import mul
from statistics import stdev as base_stdev
from typing import Generator, Iterable, List, Optional, Sequence, TypeVar

import numpy as np
import tensorflow as tf
from dateutil.parser import parse
from pytimeparse import parse as time_delta_str_to_int

from coinbase_ml.fakebase.types import Currency, ProductId, ProductVolume, QuoteVolume

GenericEnum = TypeVar("GenericEnum", bound=Enum)
Numeric = TypeVar("Numeric", float, Decimal, Fraction)


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


def convert_str_product_id(
    product_id: Optional[str],
) -> Optional[ProductId[ProductVolume, QuoteVolume]]:
    """
    convert_str_product_id [summary]

    Args:
        product_id (Optional[str]): [description]

    Returns:
        Optional[ProductId[ProductVolume, QuoteVolume]]: [description]
    """
    if product_id is not None:
        product_currency = Currency[product_id.split("-")[0]]
        quote_currency = Currency[product_id.split("-")[1]]
        return_val = ProductId[ProductVolume, QuoteVolume](
            product_currency=product_currency, quote_currency=quote_currency
        )
    else:
        return_val = None

    return return_val


def log_epsilon(num: float, epsilon: float = 1e-10) -> float:
    """
    log_epsilon [summary]

    Args:
        num (float): [description]
        epsilon (float, optional): [description]. Defaults to 1e-10.

    Returns:
        float: [description]
    """
    return log(num + epsilon)


def parse_if_not_none(dt: Optional[str]) -> datetime:
    """
    parse_if_not_none [summary]

    Args:
        dt (Optional[str]): [description]

    Returns:
        datetime: [description]
    """
    return parse(dt) if dt else None


def parse_time_delta(time_delta: str) -> Optional[timedelta]:
    """
    parse_time_delta converts a str to timedelta or returns
    None if unable to parse

    Args:
        time_delta (str): [description]

    Returns:
        Optional[timedelta]: [description]
    """
    return timedelta(seconds=time_delta_str_to_int(time_delta))


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
    tf.random.set_seed(seed)


def stdev(data: List[Numeric]) -> Numeric:
    """Basically statistics.stdev but does not throw an
    error for list of length 1. For lists of length 1 will
    return 0. Otherwise returns statistics.stdev of list.

    Args:
        data (List[Numeric]): Description

    Returns:
        Numeric: stdev
    """
    _data = data.__mul__(2) if len(data) == 1 else data

    return base_stdev(_data)
