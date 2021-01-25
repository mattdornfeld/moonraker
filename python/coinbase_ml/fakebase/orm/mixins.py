"""Summary

Attributes:
    Base (sqlalchemy.ext.declarative.api.Base): Description
"""
from datetime import datetime
from decimal import Decimal
from typing import Any, Generic, Optional, Type, Union

from sqlalchemy import Column, BigInteger, String, DateTime, Float
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.ext.hybrid import hybrid_property

from ..types import (
    Currency,
    InvalidTypeError,
    OrderSide,
    Price,
    ProductId,
    ProductPrice,
    ProductVolume,
    ProductVolumeSubType,
    QuoteVolume,
    QuoteVolumeSubType,
    Volume,
)
from ..utils.types import Numeric

Base: Any = declarative_base()


class CoinbaseEvent(Generic[ProductVolumeSubType, QuoteVolumeSubType]):

    """Mixin class for CoinbaseCancellation, CoinbaseMatch, and CoinbaseOrder
    """

    _price = Column("price", Float(asdecimal=True))
    _product_id = Column("product_id", String)
    _side = Column("side", String)
    row_id = Column(BigInteger, primary_key=True, autoincrement=True)
    time = Column(DateTime)

    def __init__(
        self,  # pylint: disable = W0613
        product_id: ProductId,
        side: OrderSide,
        time: datetime,
        price: Optional[ProductPrice] = None,
        **kwargs: Any,
    ) -> None:
        """
        __init__ [summary]

        Args:
            product_id (ProductId): [description]
            side (OrderSide): [description]
            time (datetime): [description]
            price (Optional[Price], optional): [description]. Defaults to None.
        """
        self._typed_price: Optional[ProductPrice] = None
        self._typed_product_currency: Optional[Currency] = None
        self._typed_product_id: Optional[ProductId[ProductVolume, QuoteVolume]] = None
        self._typed_quote_currency: Optional[Currency] = None
        self._product_volume_type: Optional[Type[ProductVolume]] = None
        self._product_id = str(product_id) if product_id else None
        self._price = price.amount if price else None
        # self._quote_volume_type: Optional[Type[QuoteVolume]] = None
        self._side = side.value if side else None
        self.time = time

        self._set_typed_product_info()
        self._set_typed_price()
        self._set_typed_side()

    def __repr__(self) -> str:
        """
        __repr__ [summary]

        Returns:
            str: [description]
        """
        return str(self.__dict__)

    def _set_typed_product_info(self) -> None:
        """
        _set_typed_product_info [summary]
        """
        if self._product_id:
            product_currency, quote_currency = self._product_id.split("-")
            self._typed_product_currency = Currency.from_string(product_currency)
            self._typed_quote_currency = Currency.from_string(quote_currency)
            self._typed_product_id = ProductId[ProductVolume, QuoteVolume](
                self._typed_product_currency, self._typed_quote_currency
            )
            self._product_volume_type = self._typed_product_id.product_volume_type
            # self._quote_volume_type = self._typed_product_id.quote_volume_type
        else:
            self._typed_product_currency = None
            self._typed_quote_currency = None
            self._typed_product_id = None
            self._product_volume_type = None
            # self._quote_volume_type = None

    def _set_typed_price(self) -> None:
        """
        _set_typed_price [summary]
        """
        self._typed_price = (
            None
            if self._price is None
            else self.get_product_id().price_type(self._price)
        )

    def _set_typed_side(self) -> None:
        """
        _set_typed_side [summary]
        """
        self._typed_side = OrderSide.from_string(self._side)

    def get_product_id(self) -> ProductId[ProductVolume, QuoteVolume]:
        """
        get_product_id returns the ProductId. This method exists because mypy doesn't like
        the hybrid_property decorator used on the product_id method.

        Returns:
            ProductId[ProductVolume, QuoteVolume]: [description]
        """
        return self._typed_product_id

    @property
    def product_currency(self) -> Currency:
        """Summary

        Returns:
            Currency: Description
        """
        return self._typed_product_currency

    @property
    def price(self) -> Optional[ProductPrice]:
        """Summary

        Returns:
            ProductPrice: Description
        """
        return self._typed_price

    @price.setter
    def price(self, value: Optional[Union[Numeric, ProductPrice]]) -> None:
        """
        price [summary]

        Args:
            value (Optional[Union[Numeric, ProductPrice]]): [description]

        Raises:
            InvalidTypeError: [description]
        """
        if isinstance(value, Price):
            price_value: Price = value
            self._price = price_value.amount
        elif isinstance(value, float):
            price_float: float = value
            self._price = Decimal(price_float)
        elif isinstance(value, Decimal) or value is None:
            price_optional_decimal: Optional[Decimal] = value
            self._price = price_optional_decimal
        else:
            raise InvalidTypeError(type(value), "value")

        self._set_typed_price()

    @hybrid_property
    def product_id(self) -> ProductId:
        """
        product_id [summary]

        Returns:
            ProductId: [description]
        """
        return self.get_product_id()

    @product_id.comparator  # type: ignore
    def product_id(self) -> str:
        """
        product_id [summary]

        Returns:
            str: [description]
        """
        return self._product_id

    @product_id.setter  # type: ignore
    def product_id(self, value: ProductId) -> None:
        """
        product_id [summary]

        Args:
            value (ProductId): [description]
        """
        self._product_id = str(value)

    @property
    def quote_currency(self) -> Currency:
        """Summary

        Returns:
            Currency: Description
        """
        return self._typed_quote_currency

    @property
    def side(self) -> OrderSide:
        """
        side [summary]

        Returns:
            OrderSide: [description]
        """
        return self._typed_side

    @side.setter
    def side(self, value: Union[str, OrderSide]) -> None:
        """
        side [summary]

        Args:
            value (Union[str, OrderSide]): [description]

        Raises:
            ValueError: [description]
            InvalidTypeError: [description]
        """
        if isinstance(value, str):
            if value in [OrderSide.buy.value, OrderSide.sell.value]:
                self._side = value
                self._typed_side = OrderSide.from_string(self._side)
            else:
                raise ValueError

        elif isinstance(value, OrderSide):
            self._side = value.value
            self._typed_side = value

        elif value is None:
            self._side = None
            self._typed_side = None

        else:
            raise InvalidTypeError(type(value), "value")


class MatchOrderEvent(CoinbaseEvent):

    """Mixin class for CoinbaseMatch and CoinbaseOrder
    """

    _size = Column("size", Float(asdecimal=True))

    def __init__(
        self,
        product_id: ProductId,
        side: OrderSide,
        time: datetime,
        price: Optional[ProductPrice] = None,
        size: Optional[ProductVolume] = None,
        **kwargs: Any,
    ) -> None:
        """
        __init__ [summary]

        Args:
            product_id (ProductId): [description]
            side (OrderSide): [description]
            time (datetime): [description]
            price (Optional[ProductPrice], optional): [description]. Defaults to None.
            size (Optional[QuoteVolume], optional): [description]. Defaults to None.
        """
        super().__init__(
            product_id=product_id, side=side, time=time, price=price, **kwargs
        )

        self._typed_size: Optional[ProductVolume] = None
        self.size = size
        self._set_typed_size()

    def _set_typed_size(self) -> None:
        """
        _set_typed_size [summary]
        """
        self._typed_size = (
            None
            if self._size is None
            else self.get_product_id().product_volume_type(self._size)
        )

    @property
    def size(self) -> Optional[ProductVolume]:
        """Summary

        Returns:
            Optional[Volume]: Description
        """
        return self._typed_size

    @size.setter
    def size(self, value: Optional[Union[Numeric, ProductVolume]]) -> None:
        """
        size [summary]

        Args:
            value (Optional[Union[Numeric, ProductVolume]]): [description]

        Raises:
            InvalidTypeError: [description]
        """
        if isinstance(value, Volume):
            size_value: ProductVolume = value
            self._size = size_value.amount
        elif isinstance(value, float):
            size_float: float = value
            self._size = Decimal(size_float)
        elif isinstance(value, Decimal) or value is None:
            size_optional_decimal: Optional[Decimal] = value
            self._size = size_optional_decimal
        else:
            raise InvalidTypeError(type(value), "value")

        self._set_typed_size()
