"""
 [summary]
"""
import logging
from typing import Any, Dict, List

from cbpro import WebsocketClient
from dateutil import parser

from fakebase.orm import (
    CoinbaseEvent,
    CoinbaseCancellation,
    CoinbaseMatch,
    CoinbaseOrder,
)
from fakebase.types import OrderId, OrderStatus, OrderSide, OrderType

from coinbase_ml.common import constants as cc
from coinbase_ml.common.utils import convert_str_product_id
from coinbase_ml.serve import constants as c
from coinbase_ml.serve.order_book import OrderBookBinner

LOGGER = logging.getLogger(__name__)


class CoinbaseStreamProcessor(WebsocketClient):
    """
    [summary]
    """

    def __init__(self, order_book_binner: OrderBookBinner,) -> None:
        """
        __init__ [summary]

        Args:
            order_book_binner (OrderBookBinner): [description]
        """
        super().__init__(
            channels=c.COINBASE_WEBSOCKET_CHANNELS,
            products=[str(cc.PRODUCT_ID)],
            url=c.COINBASE_WEBSOCKET_API_URL,
        )
        self.order_book_binner = order_book_binner
        self.received_orders: List[CoinbaseEvent] = []
        self.matches: List[CoinbaseMatch] = []
        self.received_cancellations: List[CoinbaseEvent] = []

    def flush(self) -> None:
        """
        flush clears the buffers self.received_orders, self.matches,
        and self.received_cancellations
        """
        self.received_orders = []
        self.matches = []
        self.received_cancellations = []

    def on_message(self, msg: Dict[str, Any]) -> None:
        """
        on_message is called in an async manner by cbpro

        Args:
            msg (Dict[str, Any]): [description]

        Returns:
            None: [description]
        """
        if msg["type"] == "snapshot":
            self.order_book_binner.insert_book_snapshot(
                {OrderSide.buy: msg["bids"], OrderSide.sell: msg["asks"]}
            )

        elif msg["type"] == "l2update":
            self.order_book_binner.process_book_changes(msg["changes"])

        elif msg["type"] == "received":
            self.received_orders.append(
                CoinbaseOrder(
                    client_oid=msg.get("client_oid"),
                    funds=msg.get("funds"),
                    order_id=OrderId(msg["order_id"]),
                    order_type=OrderType[msg["order_type"]],
                    order_status=OrderStatus[msg["type"]],
                    price=msg.get("price"),
                    product_id=convert_str_product_id(msg["product_id"]),
                    sequence=msg["sequence"],
                    side=OrderSide[msg["side"]],
                    size=msg.get("size"),
                    time=parser.parse(msg["time"]),
                )
            )

        elif msg["type"] == "match":
            self.matches.append(
                CoinbaseMatch(
                    maker_order_id=OrderId(msg["maker_order_id"]),
                    price=msg["price"],
                    product_id=convert_str_product_id(msg["product_id"]),
                    sequence=msg["sequence"],
                    side=OrderSide[msg["side"]],
                    size=msg["size"],
                    taker_order_id=OrderId(msg["taker_order_id"]),
                    time=parser.parse(msg["time"]),
                    trade_id=msg["trade_id"],
                )
            )

        elif msg["type"] == "done" and msg["reason"] == "canceled":
            self.received_cancellations.append(
                CoinbaseCancellation(
                    order_id=OrderId(msg["order_id"]),
                    price=msg["price"],
                    product_id=convert_str_product_id(msg["product_id"]),
                    remaining_size=msg["remaining_size"],
                    side=msg["side"],
                    time=parser.parse(msg["time"]),
                )
            )

        else:
            LOGGER.debug("Error: message type not known: %s", msg)

    def on_open(self) -> None:
        """
        on_open [summary]
        """
        LOGGER.info(
            "Subscribed to the %s channels, for the %s product, from the %s feed.",
            c.COINBASE_WEBSOCKET_CHANNELS,
            str(cc.PRODUCT_ID),
            c.COINBASE_WEBSOCKET_API_URL,
        )
