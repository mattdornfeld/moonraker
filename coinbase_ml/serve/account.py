"""
 [summary]
"""
from datetime import timedelta
from decimal import Decimal
import logging
from typing import Any, Dict, List

from cbpro import AuthenticatedClient

import fakebase.constants
from fakebase.base_classes import AccountBase, Funds
from fakebase.orm import CoinbaseOrder

from coinbase_ml.common import constants as cc
from coinbase_ml.common.utils import parse_if_not_none
from coinbase_ml.serve import constants as c

LOGGER = logging.getLogger(__name__)


class Account(AccountBase):
    """
     [summary]
    """

    def __init__(self) -> None:
        self.client = AuthenticatedClient(
            key=c.COINBASE_API_KEY_NAME,
            b64secret=c.COINBASE_API_KEY_B64SECRET,
            passphrase=c.COINBASE_API_KEY_PASSPHRASE,
            api_url=c.COINBASE_API_URL,
        )

        # Coinbase has limited functionality around order expiration
        # so here we keep track of order expiration times and implement
        # our own logic for canceling expired orders
        self.order_time_to_live: Dict[str, timedelta] = {}

    @staticmethod
    def _make_order_object(order_info: Dict[str, Any]) -> CoinbaseOrder:
        return CoinbaseOrder(
            order_id=order_info.get("id"),
            order_status=order_info.get("status"),
            order_type=order_info.get("type"),
            post_only=order_info.get("post_only"),
            price=order_info.get("price"),
            product_id=order_info.get("product_id"),
            side=order_info.get("side"),
            size=order_info.get("size"),
            time=parse_if_not_none(order_info.get("created_at")),
            time_in_force=order_info.get("time_in_force"),
            time_to_live=order_info.get("time_to_live"),
        )

    def cancel_order(self, order_id: str) -> CoinbaseOrder:
        LOGGER.info("Canceling order %s", order_id)
        order_info: Dict[str, Any] = self.client.get_order(order_id)
        order_info["status"] = "canceled"
        self.client.cancel_order(order_id)

        if order_id in self.order_time_to_live:
            del self.order_time_to_live[order_id]

        return self._make_order_object(order_info)

    @property
    def funds(self) -> Dict[str, Funds]:
        _funds: Dict[str, Funds] = {}
        for fund in self.get_accounts():
            _funds[fund["currency"]] = Funds(
                balance=Decimal(fund["balance"]),
                currency=fund["currency"],
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
        return [
            account
            for account in self.client.get_accounts()
            if account["currency"] in AccountBase.currencies
        ]

    @property
    def orders(self) -> Dict[str, CoinbaseOrder]:
        """
        orders [summary]

        Returns:
            Dict[str, CoinbaseOrder]: [description]
        """
        orders: Dict[str, CoinbaseOrder] = {}
        for order_info in self.client.get_orders(product_id=cc.PRODUCT_ID):
            order_id = order_info["id"]
            order_info["time_to_live"] = self.order_time_to_live.get(
                order_id, timedelta(seconds=0)
            )
            orders[order_id] = self._make_order_object(order_info)

        return orders

    def place_limit_order(
        self,
        product_id: str,
        price: Decimal,
        side: str,
        size: Decimal,
        post_only: bool = False,
        time_in_force: str = "GTC",
        time_to_live: timedelta = timedelta.max,
    ) -> CoinbaseOrder:
        """
        place_limit_order [summary]

        Args:
            product_id (str): [description]
            price (Decimal): [description]
            side (str): [description]
            size (Decimal): [description]
            post_only (bool, optional): [description]. Defaults to False.
            time_in_force (str, optional): [description]. Defaults to "GTC".
            time_to_live (timedelta, optional): [description]. Defaults to timedelta.max.

        Returns:
            CoinbaseOrder: [description]
        """
        order_info: Dict[str, Any] = self.client.place_limit_order(
            product_id=fakebase.constants.PRODUCT_ID,
            side=side,
            price=str(price),
            size=str(size),
        )

        order_info["time_to_live"] = time_to_live
        order = self._make_order_object(order_info)

        if not order.order_id:
            order.reject_order(order_info.get("message"))
            LOGGER.info("Order rejected: %s", order.reject_reason)
        else:
            self.order_time_to_live[order.order_id] = time_to_live

        return order
