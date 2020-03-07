"""
 [summary]
"""
from datetime import datetime, timedelta
import logging
from typing import TYPE_CHECKING, Any, Dict, Optional

from cbpro import AuthenticatedClient
from dateutil.parser import parse
from dateutil.tz import UTC

from coinbase_ml.common import constants as cc
from coinbase_ml.common.utils import (
    convert_str_product_id,
    parse_if_not_none,
)
from coinbase_ml.fakebase.base_classes.account import AccountBase, Funds
from coinbase_ml.fakebase.orm import CoinbaseOrder
from coinbase_ml.fakebase.types import (
    Currency,
    OrderId,
    OrderSide,
    OrderStatus,
    OrderType,
    ProductId,
    ProductPrice,
    ProductVolume,
)
from coinbase_ml.fakebase.types.currency.volume import VOLUMES
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
    def _convert_to_price_if_not_none(
        price_str: Optional[str],
    ) -> Optional[ProductPrice]:
        """
        _convert_to_price_if_not_none [summary]

        Args:
            price_str (Optional[str]): [description]

        Returns:
            Optional[ProductPrice]: [description]
        """
        return None if price_str is None else cc.PRODUCT_ID.price_type(price_str)

    @staticmethod
    def _convert_to_product_volume_if_not_none(
        volume_str: Optional[str],
    ) -> Optional[ProductVolume]:
        """
        _convert_to_product_volume_if_not_none [summary]

        Args:
            volume_str (Optional[str]): [description]

        Returns:
            Optional[ProductVolume]: [description]
        """
        return (
            None
            if volume_str is None
            else cc.PRODUCT_ID.product_volume_type(volume_str)
        )

    @staticmethod
    def _make_order_object(order_info: Dict[str, Any]) -> CoinbaseOrder:
        """
        _make_order_object [summary]

        Args:
            order_info (Dict[str, Any]): [description]

        Returns:
            CoinbaseOrder: [description]
        """
        return CoinbaseOrder(
            order_id=OrderId(order_info.get("id")),
            order_status=OrderStatus.from_string(order_info.get("order_status")),
            order_type=OrderType.from_string(order_info.get("type")),
            post_only=bool(order_info.get("post_only")),
            price=Account._convert_to_price_if_not_none(order_info.get("price")),
            product_id=convert_str_product_id(order_info.get("product_id")),
            side=OrderSide.from_string(order_info.get("side")),
            size=Account._convert_to_product_volume_if_not_none(order_info.get("size")),
            time=parse_if_not_none(order_info.get("created_at")),
            time_in_force=order_info.get("time_in_force"),
            time_to_live=order_info.get("time_to_live"),
        )

    def cancel_order(self, order_id: OrderId) -> CoinbaseOrder:
        """
        cancel_order [summary]

        Args:
            order_id (OrderId): [description]

        Returns:
            CoinbaseOrder: [description]
        """
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
        for currency_str, fund in self.get_accounts().items():
            currency = Currency[currency_str]
            VolumeType = VOLUMES[currency]  # pylint: disable=invalid-name

            _funds[currency] = Funds(
                balance=VolumeType(fund["balance"]),
                currency=currency,
                holds=VolumeType(fund["hold"]),
                id=fund["id"],
            )

        return _funds

    def get_accounts(self) -> Dict[str, Dict[str, str]]:
        """
        get_accounts [summary]

        Returns:
            Dict[str, Dict[str, str]]: [description]
        """
        accounts: Dict[str, Dict[str, str]] = {}
        for account in self.client.get_accounts():
            try:
                currency = Currency[account["currency"]]
            except KeyError:
                continue

            if currency in self.currencies:
                accounts[account["currency"]] = account

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
        price: ProductPrice,
        side: OrderSide,
        size: ProductVolume,
        post_only: bool = False,
        time_in_force: str = "GTC",
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
            time_in_force (str, optional): [description]. Defaults to "GTC".
            time_to_live (timedelta, optional): [description]. Defaults to timedelta.max.

        Returns:
            CoinbaseOrder: [description]
        """
        order_info: Dict[str, Any] = self.client.place_limit_order(
            product_id=str(self.exchange.product_id),
            side=side.value,
            price=str(price.amount),
            size=str(size.amount),
        )

        # Occasionaly Coinbase can send back a timestamp with an invalid timezone
        # If this happens replace it with the current utc time. The times should be
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
