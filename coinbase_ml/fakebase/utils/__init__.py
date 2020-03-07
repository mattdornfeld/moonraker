"""Summary
"""
import random
from decimal import Decimal
from random import getrandbits
from typing import Any, Optional, Sequence, Union, cast
from uuid import UUID

import numpy as np

from coinbase_ml.fakebase import constants as c
from coinbase_ml.fakebase.utils.types import Numeric
from coinbase_ml.fakebase.types import OrderId, OrderSide


def convert_to_decimal_if_not_none(value: Optional[Numeric]) -> Optional[Decimal]:
    """Summary

    Args:
        value (Optional[Numeric]): Description

    Returns:
        Optional[Decimal]: Description
    """
    return Decimal(value) if value is not None else None


def generate_order_id() -> OrderId:
    """
    generate_order_id [summary]

    Returns:
        OrderId: [description]
    """
    return OrderId(str(UUID(int=getrandbits(128))))


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
