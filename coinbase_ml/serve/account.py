"""
 [summary]
"""
from datetime import datetime, timedelta
from decimal import Decimal
import logging
from typing import TYPE_CHECKING, Any, Dict, List

from cbpro import AuthenticatedClient
from dateutil.parser import parse
from dateutil.tz import UTC

from fakebase.base_classes.account import AccountBase, Funds
from fakebase.orm import CoinbaseOrder
from fakebase.types import (
    Currency,
    OrderId,
    OrderSide,
    OrderStatus,
    OrderType,
    ProductId,
)
from fakebase.utils import convert_to_decimal_if_not_none

from coinbase_ml.common import constants as cc
from coinbase_ml.common.utils import (
    convert_to_enum,
    convert_str_product_id,
    parse_if_not_none,
)
from coinbase_ml.serve import constants as c

LOGGER = logging.getLogger(__name__)

if TYPE_CHECKING:
    # To avoid circular import exchange on the submodule level
    # Exchange is then referenced using the str syntax below
    import coinbase_ml.serve.exchange


class Account(AccountBase["coinbase_ml.serve.exchange.Exchange"]):
    """
     [summary]
    """

    def __init__(self, exchange: "coinbase_ml.serve.exchange.Exchange") -> None:
        super().__init__(exchange)

        self.client = AuthenticatedClient(
            key=c.COINBASE_API_KEY_NAME,
            b64secret=c.COINBASE_API_KEY_B64SECRET,
            passphrase=c.COINBASE_API_KEY_PASSPHRASE,
            api_url=c.COINBASE_API_URL,
        )

        # Coinbase has limited functionality around order expiration
        # so here we keep track of order expiration times and implement
        # our own logic for canceling expired orders
        self.order_time_to_live: Dict[OrderId, timedelta] = {}

    @staticmethod
    def _make_order_object(order_info: Dict[str, Any]) -> CoinbaseOrder:
        return CoinbaseOrder(
            order_id=OrderId(order_info.get("id")),
            order_status=convert_to_enum(OrderStatus, order_info.get("status")),
            order_type=convert_to_enum(OrderType, order_info.get("type")),
            post_only=bool(order_info.get("post_only")),
            price=convert_to_decimal_if_not_none(order_info.get("price")),
            product_id=convert_str_product_id(order_info.get("product_id")),
            side=convert_to_enum(OrderSide, order_info.get("side")),
            size=convert_to_decimal_if_not_none(order_info.get("size")),
            time=parse_if_not_none(order_info.get("created_at")),
            time_in_force=order_info.get("time_in_force"),
            time_to_live=order_info.get("time_to_live"),
        )

    def cancel_order(self, order_id: OrderId) -> CoinbaseOrder:
        LOGGER.info("Canceling order %s", order_id)
        order_info: Dict[str, Any] = self.client.get_order(order_id)
        order_info["status"] = "canceled"
        self.client.cancel_order(order_id)

        if order_id in self.order_time_to_live:
            del self.order_time_to_live[order_id]

        return self._make_order_object(order_info)

    @property
    def funds(self) -> Dict[Currency, Funds]:
        """
        funds [summary]

        Returns:
            Dict[Currency, Funds]: [description]
        """
        _funds: Dict[Currency, Funds] = {}
        for fund in self.get_accounts():
            currency = Currency[fund["currency"]]
            _funds[currency] = Funds(
                balance=Decimal(fund["balance"]),
                currency=currency,
                holds=Decimal(fund["hold"]),
                id=fund["id"],
            )

        return _funds

    def get_accounts(self) -> List[Dict[str, str]]:
        """
        get_accounts [summary]

        Returns:
            List[Dict[str, str]]: [description]
        """
        accounts = []
        for account in self.client.get_accounts():
            try:
                currency = Currency[account["currency"]]
            except KeyError:
                continue

            if currency in self.currencies:
                accounts.append(account)

        return accounts

    @property
    def orders(self) -> Dict[OrderId, CoinbaseOrder]:
        """
        orders [summary]

        Returns:
            Dict[OrderId, CoinbaseOrder]: [description]
        """
        orders: Dict[OrderId, CoinbaseOrder] = {}
        for order_info in self.client.get_orders(product_id=cc.PRODUCT_ID):
            order_id = OrderId(order_info["id"])
            order_info["time_to_live"] = self.order_time_to_live.get(
                order_id, timedelta(seconds=0)
            )
            orders[order_id] = self._make_order_object(order_info)

        return orders

    def place_limit_order(
        self,
        product_id: ProductId,
        price: Decimal,
        side: OrderSide,
        size: Decimal,
        post_only: bool = False,
        time_in_force: str = "GTC",
        time_to_live: timedelta = timedelta.max,
    ) -> CoinbaseOrder:
        """
        place_limit_order [summary]

        Args:
            product_id (ProductId): [description]
            price (Decimal): [description]
            side (OrderSide): [description]
            size (Decimal): [description]
            post_only (bool, optional): [description]. Defaults to False.
            time_in_force (str, optional): [description]. Defaults to "GTC".
            time_to_live (timedelta, optional): [description]. Defaults to timedelta.max.

        Returns:
            CoinbaseOrder: [description]
        """
        order_info: Dict[str, Any] = self.client.place_limit_order(
            product_id=str(self.exchange.product_id),
            side=side.value,
            price=str(price),
            size=str(size),
        )

        # Occasionaly Coinbase can send back a timestamp with an invalid timezone
        # If this happens replace it with the current utc time. The time should be
        # very close to each other.
        if "created_at" in order_info:
            try:
                parse(order_info["created_at"])
            except ValueError:
                order_info["created_at"] = str(datetime.now(UTC))

        order_info["time_to_live"] = time_to_live
        order = self._make_order_object(order_info)

        if not order.order_id:
            order.reject_order(order_info.get("message"))
            LOGGER.info("Order rejected: %s", order.reject_reason)
        else:
            self.order_time_to_live[order.order_id] = time_to_live

        return order
