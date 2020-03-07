"""
Module for representing products and their prices
"""
from __future__ import annotations
from dataclasses import dataclass
from decimal import Decimal
from typing import Dict, Generic, Type, TypeVar, Union

from .precise_number import PreciseNumber
from .volume import VOLUMES, BTC, Currency, ETH, Volume, USD
from .utils import InvalidTypeError

ProductVolumeSubType = TypeVar("ProductVolumeSubType", bound="Volume")
QuoteVolumeSubType = TypeVar("QuoteVolumeSubType", bound="Volume")


@dataclass
class ProductId(Generic[ProductVolumeSubType, QuoteVolumeSubType]):
    """
    A product consists of a (product_currency, quote_currency) pair.
    The quote currency is used to purchase the product currency.
    """

    product_currency: Currency
    quote_currency: Currency

    def __hash__(self) -> int:
        """
        __hash__ [summary]

        Returns:
            int: [description]
        """
        return (self.quote_currency, self.product_currency).__hash__()

    def __str__(self) -> str:
        """
        __str__ [summary]

        Returns:
            str: [description]
        """
        return f"{self.product_currency.value}-{self.quote_currency.value}"

    @property
    def max_price(self) -> ProductPrice:
        """
        max_price [summary]

        Returns:
            ProductPrice: [description]
        """
        return PRICES[self].get_max_value()

    @property
    def min_price(self) -> ProductPrice:
        """
        min_price [summary]

        Returns:
            ProductPrice: [description]
        """
        return PRICES[self].get_min_value()

    @property
    def price_type(self) -> Type[ProductPrice]:
        """
        price_type [summary]

        Returns:
            Type[Price]: [description]
        """
        return PRICES[self]

    @property
    def product_volume_type(self) -> Type[ProductVolume]:
        """
        product_volume_type [summary]

        Returns:
            Type[ProductVolume]: [description]
        """
        return VOLUMES[self.product_currency]

    @property
    def quote_volume_type(self) -> Type[QuoteVolume]:
        """
        quote_volume_type [summary]

        Returns:
            Type[QuoteVolume]: [description]
        """
        return VOLUMES[self.quote_currency]

    @property
    def zero_price(self) -> ProductPrice:
        """
        zero_price [summary]

        Returns:
            ProductPrice: [description]
        """
        return PRICES[self].get_zero_price()


class Price(
    PreciseNumber[ProductVolumeSubType],
    Generic[ProductVolumeSubType, QuoteVolumeSubType],
):
    """
    Price represents the price of a currency pair
    """

    max_value: Decimal
    min_value: Decimal
    product_id: ProductId[ProductVolumeSubType, QuoteVolumeSubType]

    def __add__(self, other: ProductPrice) -> ProductPrice:
        """
        __add__ [summary]

        Args:
            other (ProductPrice): [description]

        Raises:
            InvalidTypeError: [description]

        Returns:
            ProductPrice: [description]
        """
        if isinstance(other, Price):
            return_val = PRICES[self.product_id](self.amount + other.amount)
        else:
            raise InvalidTypeError(type(other), "other")

        return return_val

    def __truediv__(self, other: Union[Decimal, float]) -> ProductPrice:
        """
        __div__ [summary]

        Args:
            other (Union[Decimal, float]): [description]

        Raises:
            InvalidTypeError: [description]

        Returns:
            ProductPrice: [description]
        """
        if isinstance(other, Decimal):
            return_val = PRICES[self.product_id](self.amount / other)
        elif isinstance(other, (float, int)):
            return_val = PRICES[self.product_id](self.amount / Decimal(other))
        else:
            raise InvalidTypeError(type(other), "other")

        return return_val

    def __mul__(self, other: ProductVolume) -> QuoteVolume:
        """
        __mul__ accepts a variable with the same type as the product currency and
        returns a variable with the same type as the quote currency

        Args:
            other (ProductVolume): [description]

        Returns:
            QuoteVolume: [description]
        """
        assert other.currency == self.product_id.product_currency

        return VOLUMES[self.product_id.quote_currency](self.amount * other.amount)

    def __rmul__(self, other: ProductVolume) -> QuoteVolume:
        """
        __rmul__ accepts a variable with the same type as the product currency and
        returns a variable with the same type as the quote currency

        Args:
            other (ProductVolume): [description]

        Returns:
            QuoteVolume: [description]
        """
        return self.__mul__(other)

    @classmethod
    def get_max_value(cls) -> ProductPrice:
        """
        get_max_value [summary]

        Raises:
            AttributeError: [description]

        Returns:
            ProductPrice: [description]
        """
        if not (hasattr(cls, "product_id") and hasattr(cls, "max_value")):
            raise AttributeError(
                "Class must have attributes 'product_id' and 'max_value'."
            )

        return PRICES[cls.product_id](cls.max_value)

    @classmethod
    def get_min_value(cls) -> ProductPrice:
        """
        get_min_value [summary]

        Raises:
            AttributeError: [description]

        Returns:
            ProductPrice: [description]
        """
        if not (hasattr(cls, "product_id") and hasattr(cls, "min_value")):
            raise AttributeError(
                "Class must have attributes 'product_id' and 'min_value'."
            )

        return PRICES[cls.product_id](cls.min_value)

    @classmethod
    def get_zero_price(cls) -> ProductPrice:
        """
        get_zero_price [summary]

        Returns:
            ProductPrice: [description]
        """
        return ZERO_PRICES[cls.product_id]


class BTCUSD(Price[BTC, USD]):
    """
    BTCUSD price
    """

    max_value = Decimal("1e10")
    min_value = Decimal("1e-2")
    product_id = ProductId[ProductVolumeSubType, QuoteVolumeSubType](
        Currency.BTC, Currency.USD
    )
    precision = USD.precision


class ETHUSD(Price[ETH, USD]):
    """
    ETHUSD price
    """

    max_value = Decimal("1e10")
    min_value = Decimal("1e-2")
    product_id = ProductId[ProductVolumeSubType, QuoteVolumeSubType](
        Currency.BTC, Currency.USD
    )
    precision = USD.precision


ProductTypes = Union[BTC, ETH]

ProductVolume = Volume[ProductTypes]

QuoteVolume = Volume[USD]

ProductPrice = Price[ProductTypes, USD]

PRICES: Dict[ProductId, Type[Price]] = {ProductId(Currency.BTC, Currency.USD): BTCUSD}
ZERO_PRICES = {currency: Price("0.0") for currency, Price in PRICES.items()}
