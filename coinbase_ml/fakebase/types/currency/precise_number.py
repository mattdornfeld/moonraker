"""
Module for representing numbers with a defined precision.
Useful for representing prices and volumes.
"""
from __future__ import annotations
from decimal import Decimal, ROUND_HALF_UP
from math import inf
from typing import Any, Generic, TypeVar, Union

from .utils import InvalidTypeError

PreciseNumberSubtype = TypeVar("PreciseNumberSubtype", bound="PreciseNumber")


class PreciseNumber(Generic[PreciseNumberSubtype]):
    """
    PreciseNumber is an abstract class used for representing numbers with a
    specified precision. This is subclassed to represent Volume and Price
    types.
    """

    precision: Decimal

    def __init__(self, value: Union[str, Decimal]) -> None:
        """
        __init__ [summary]

        Args:
            value (Union[str, Decimal]): [description]

        Raises:
            AttributeError: [description]
            InvalidTypeError: [description]
        """
        if not hasattr(self, "precision"):
            raise AttributeError(
                "Invalid class. precision must be defined as a class variable."
            )

        if isinstance(value, Decimal):
            decimal_value: Decimal = value
            amount = decimal_value
        elif isinstance(value, str):
            str_value: str = value
            amount = Decimal(str_value)
        else:
            raise InvalidTypeError(type(value), "value")

        self.amount = (
            amount.quantize(self.precision, ROUND_HALF_UP) if amount < inf else amount
        )

        self.float_amount = float(self.amount)

    def __eq__(  # type: ignore[override]
        self, other: Any
    ) -> bool:
        """
        __eq__ [summary]

        Args:
            other (PreciseNumber[PreciseNumberSubtype]): [description]

        Raises:
            InvalidTypeError: [description]

        Returns:
            bool: [description]
        """
        if isinstance(other, PreciseNumber):
            _other: PreciseNumber[PreciseNumberSubtype] = other
            return_val = self.amount == _other.amount
        else:
            raise InvalidTypeError(type(other), "other")

        return return_val

    def __ge__(self, other: PreciseNumber[PreciseNumberSubtype]) -> bool:
        """
        __ge__ [summary]

        Args:
            other (PreciseNumber[PreciseNumberSubtype]): [description]

        Returns:
            bool: [description]
        """
        return self.amount >= other.amount

    def __gt__(self, other: PreciseNumber[PreciseNumberSubtype]) -> bool:
        """
        __gt__ [summary]

        Args:
            other (PreciseNumber[PreciseNumberSubtype]): [description]

        Returns:
            bool: [description]
        """
        return self.amount > other.amount

    def __hash__(self) -> int:
        """
        __hash__ [summary]

        Returns:
            int: [description]
        """
        return self.amount.__hash__()

    def __le__(self, other: PreciseNumber[PreciseNumberSubtype]) -> bool:
        """
        __le__ [summary]

        Args:
            other (PreciseNumber[PreciseNumberSubtype]): [description]

        Returns:
            bool: [description]
        """
        return self.amount <= other.amount

    def __lt__(self, other: PreciseNumber[PreciseNumberSubtype]) -> bool:
        """
        __lt__ [summary]

        Args:
            other (PreciseNumber[PreciseNumberSubtype]): [description]

        Returns:
            bool: [description]
        """
        return self.amount < other.amount

    def __ne__(  # type: ignore[override]
        self, other: PreciseNumber[PreciseNumberSubtype]
    ) -> bool:
        """
        __ne__ [summary]

        Args:
            other (PreciseNumber[PreciseNumberSubtype]): [description]

        Returns:
            bool: [description]
        """
        return not self == other

    def __neg__(self) -> PreciseNumber[PreciseNumberSubtype]:
        """
        __neg__ [summary]

        Returns:
            PreciseNumber[PreciseNumberSubtype]: [description]
        """
        return self.__class__(-self.amount)

    def __pos__(self) -> PreciseNumber[PreciseNumberSubtype]:
        """
        __pos__ [summary]

        Returns:
            PreciseNumber[PreciseNumberSubtype]: [description]
        """
        return self.__class__(+self.amount)

    def __repr__(self) -> str:
        """
        __repr__ [summary]

        Returns:
            str: [description]
        """
        return "<{} {}>".format(self.__class__.__name__, self.amount)

    def __str__(self) -> str:
        """
        __str__ [summary]

        Returns:
            str: [description]
        """
        return str(self.amount)

    def is_too_large(self) -> bool:
        """
        is_too_large returns True if amount is less than min
        allowed value. Used to validate order prices and sizes.

        Returns:
            bool: [description]
        """
        return self > self.get_max_value()

    def is_too_small(self) -> bool:
        """
        is_too_small returns True if amount is greater than max
        allowed value. Used to validate order prices and sizes.

        Returns:
            bool: [description]
        """
        return self < self.get_min_value()

    @classmethod
    def get_max_value(cls) -> PreciseNumber:
        """
        get_max_value [summary]

        Raises:
            NotImplementedError: [description]

        Returns:
            PreciseNumber: [description]
        """
        raise NotImplementedError

    @classmethod
    def get_min_value(cls) -> PreciseNumber:
        """
        get_min_value [summary]

        Raises:
            NotImplementedError: [description]

        Returns:
            PreciseNumber: [description]
        """
        raise NotImplementedError
