"""
Base classes for Account and Exchange
"""
from __future__ import annotations
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import TYPE_CHECKING, Dict, Generic, List, Optional, TypeVar

from fakebase.orm import CoinbaseOrder
from fakebase.types import (
    Currency,
    OrderId,
    OrderSide,
    OrderStatus,
    ProductId,
    ProductPrice,
    ProductVolume,
    Volume,
)

if TYPE_CHECKING:
    # To avoid circular import exchange on the submodule level
    # ExchangeBase is then referenced using the str syntax below
    import fakebase.base_classes

Exchange = TypeVar("Exchange", bound="fakebase.base_classes.ExchangeBase")


class AccountBase(Generic[Exchange]):
    """
    AccountBase abstract class
    """

    def __init__(self, exchange: Exchange) -> None:
        self.currencies = [
            exchange.product_id.quote_currency,
            exchange.product_id.product_currency,
        ]

        self.exchange = exchange

    def cancel_expired_orders(
        self, current_dt: Optional[datetime] = None
    ) -> List[CoinbaseOrder]:
        """
        cancel_expired_orders [summary]

        Args:
            current_dt (Optional[datetime], optional): [description]. Defaults to None.

        Returns:
            List[CoinbaseOrder]: [description]
        """
        current_dt = current_dt if current_dt else self.exchange.interval_start_dt
        cancelled_orders: List[CoinbaseOrder] = []

        # Call list on the below dict so that this function does
        # not change the size of the dict during iteration
        print(list(self.orders.values()))
        for order in list(self.orders.values()):
            if order.order_status != OrderStatus.open:
                continue

            deadline = (
                datetime.max
                if order.time_to_live == timedelta.max
                else order.time + order.time_to_live
            )

            if current_dt >= deadline:
                cancelled_orders.append(self.cancel_order(order.order_id))

        return cancelled_orders

    def cancel_order(self, order_id: OrderId) -> CoinbaseOrder:
        """
        cancel_order [summary]

        Args:
            order_id (OrderId): [description]

        Raises:
            NotImplementedError: [description]

        Returns:
            CoinbaseOrder: [description]
        """
        raise NotImplementedError

    @property
    def funds(self) -> Dict[Currency, Funds]:
        """
        funds [summary]

        Raises:
            NotImplementedError: [description]

        Returns:
            Dict[Currency, Funds]: [description]
        """
        raise NotImplementedError

    def get_accounts(self) -> Dict[str, Dict[str, str]]:
        """
        get_accounts [summary]

        Raises:
            NotImplementedError: [description]

        Returns:
            Dict[str, Dict[str, str]]: [description]
        """
        raise NotImplementedError

    @property
    def orders(self) -> Dict[OrderId, CoinbaseOrder]:
        """
        orders [summary]

        Raises:
            NotImplementedError: [description]

        Returns:
            Dict[OrderId, CoinbaseOrder]: [description]
        """
        raise NotImplementedError

    def place_limit_order(
        self,
        product_id: ProductId,
        price: ProductPrice,
        side: OrderSide,
        size: ProductVolume,
        post_only: bool = False,
        time_in_force: str = "gtc",
        time_to_live: timedelta = timedelta.max,
    ) -> CoinbaseOrder:
        """
        place_limit_order [summary]

        Args:
            product_id (ProductId): [description]
            price (ProductPrice): [description]
            side (OrderSide): [description]
            size (ProductVolume): [description]
            post_only (bool, optional): [description]. Defaults to False.
            time_in_force (str, optional): [description]. Defaults to "gtc".
            time_to_live (timedelta, optional): [description]. Defaults to timedelta.max.

        Raises:
            NotImplementedError: [description]

        Returns:
            CoinbaseOrder: [description]
        """
        raise NotImplementedError


@dataclass
class Funds:
    """
    Encapsulates the balance/holds of a single product
    """

    id: str
    balance: Volume
    currency: Currency
    holds: Volume
