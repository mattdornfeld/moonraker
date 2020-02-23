"""
Module for representing currencies and their volumes.
"""
from __future__ import annotations

from decimal import Decimal
from enum import Enum
from typing import Dict, Type, TypeVar

from .precise_number import PreciseNumber
from .utils import InvalidTypeError

VolumeSubType = TypeVar("VolumeSubType", bound="Volume")


class Currency(Enum):
    """
    Currency contains the different currency ids
    """

    BTC = "BTC"
    ETH = "ETH"
    USD = "USD"

    @property
    def volume_type(self) -> Type[Volume[VolumeSubType]]:
        """
        volume_type this returns the class representing the
        volume for this currency. This should only be called by
        `.price.ProductId`. Use the product_volume_type and quote_volume_type
        methods on `.price.ProductId` instead.

        Returns:
            Type[Volume]: [description]
        """
        return VOLUMES[self]

    @property
    def zero_volume(self) -> Volume[VolumeSubType]:
        """
        zero_volume [summary]

        Returns:
            Volume: [description]
        """
        return VOLUMES[self].get_zero_volume()


class Volume(PreciseNumber[VolumeSubType]):
    """
    Volume is used to represent volumes of currency
    """

    currency: Currency
    max_value: Decimal
    min_value: Decimal

    def __add__(self, other: Volume[VolumeSubType]) -> Volume[VolumeSubType]:
        """
        __add__ [summary]

        Args:
            other (Volume[VolumeSubType]): [description]

        Returns:
            Volume[VolumeSubType]: [description]
        """
        if not isinstance(other, type(self)):
            raise InvalidTypeError(type(other), "other")

        return self.__class__(self.amount + other.amount)

    def __mul__(self, other: Decimal) -> Volume[VolumeSubType]:
        """
        __mul__ [summary]

        Args:
            other (Decimal): [description]

        Raises:
            InvalidTypeError: [description]

        Returns:
            Volume[VolumeSubType]: [description]
        """
        return_val: Volume[VolumeSubType]
        if isinstance(other, Decimal):
            decimal_other: Decimal = other
            return_val = self.__class__(self.amount * decimal_other)
        else:
            raise InvalidTypeError(type(other), "other")

        return return_val

    def __radd__(self, other: Volume[VolumeSubType]) -> Volume:
        """
        __radd__ [summary]

        Args:
            other (Decimal): [description]

        Returns:
            Volume[VolumeSubType]: [description]
        """
        return self.__add__(other)

    def __rmul__(self, other: Decimal) -> Volume[VolumeSubType]:
        """
        __rmul__ [summary]

        Args:
            other (Union[Volume[VolumeSubType], Decimal]): [description]

        Returns:
            Volume[VolumeSubType]: [description]
        """
        return self.__mul__(other)

    def __rsub__(self, other: Volume[VolumeSubType]) -> Volume[VolumeSubType]:
        """
        __rsub__ [summary]

        Args:
            other (Volume[VolumeSubType]): [description]

        Returns:
            Volume[VolumeSubType]: [description]
        """
        return self.__sub__(other)

    def __sub__(self, other: Volume[VolumeSubType]) -> Volume[VolumeSubType]:
        """
        __sub__ [summary]

        Args:
            other (Volume[VolumeSubType]): [description]

        Returns:
            Volume[VolumeSubType]: [description]
        """
        if not isinstance(other, type(self)):
            raise InvalidTypeError(type(other), "other")

        return self.__class__(self.amount - other.amount)

    def is_zero(self) -> bool:
        """
        is_zero [summary]

        Returns:
            bool: [description]
        """
        return self.amount == 0

    @classmethod
    def get_max_value(cls) -> Volume[VolumeSubType]:
        """
        get_max_value [summary]

        Raises:
            AttributeError: [description]

        Returns:
            Volume[VolumeSubType]: [description]
        """
        if not (hasattr(cls, "currency") and hasattr(cls, "max_value")):
            raise AttributeError(
                "Class must have attributes 'currency' and 'max_value'."
            )

        return VOLUMES[cls.currency](cls.max_value)

    @classmethod
    def get_min_value(cls) -> Volume[VolumeSubType]:
        """
        get_min_value [summary]

        Raises:
            AttributeError: [description]

        Returns:
            Volume[VolumeSubType]: [description]
        """
        if not (hasattr(cls, "currency") and hasattr(cls, "min_value")):
            raise AttributeError(
                "Class must have attributes 'currency' and 'min_value'."
            )

        return VOLUMES[cls.currency](cls.min_value)

    @classmethod
    def get_zero_volume(cls) -> Volume[VolumeSubType]:
        """
        get_zero_volume [summary]

        Returns:
            Volume[VolumeSubType]: [description]
        """
        return ZERO_VOLUMES[cls.currency]


class USD(Volume["USD"]):
    """
    USD volume type
    """

    currency = Currency.USD
    max_value = Decimal("1e10")
    min_value = Decimal("1e-2")
    precision = 2


class BTC(Volume["BTC"]):
    """
    BTC volume type
    """

    currency = Currency.BTC
    max_value = Decimal("1e4")
    min_value = Decimal("1e-3")
    precision = 8


class ETH(Volume["ETH"]):
    """
    ETH volume type
    TODO: Revist these values
    """

    currency = Currency.ETH
    max_value = Decimal("1e4")
    min_value = Decimal("1e-3")
    precision = 8


VOLUMES: Dict[Currency, Type[Volume]] = {
    Currency.USD: USD,
    Currency.BTC: BTC,
    Currency.ETH: ETH,
}

ZERO_VOLUMES = {currency: Volume("0.0") for currency, Volume in VOLUMES.items()}
