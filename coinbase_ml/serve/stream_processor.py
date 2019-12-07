"""
 [summary]
"""
import logging
from typing import Any, Dict, List

from cbpro import WebsocketClient
from dateutil import parser

from fakebase.orm import CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder

from coinbase_ml.common import constants as cc
from coinbase_ml.serve import constants as c
from coinbase_ml.serve.order_book import OrderBookBinner

LOGGER = logging.getLogger(__name__)


class CoinbaseStreamProcessor(WebsocketClient):
    """
    [summary]
    """

    def __init__(
        self,
        matches: List[CoinbaseMatch],
        order_book_binner: OrderBookBinner,
        received_cancellations: List[CoinbaseCancellation],
        received_orders: List[CoinbaseOrder],
    ) -> None:
        """
        __init__ [summary]

        Args:
            order_book (BinnedOrderBook): [description]
            received_orders (List[CoinbaseOrder]): [description]
            matches (List[CoinbaseMatch]): [description]
            received_cancellations (List[CoinbaseCancellation]): [description]
        """
        super().__init__(
            channels=c.COINBASE_WEBSOCKET_CHANNELS,
            products=[cc.PRODUCT_ID],
            url=c.COINBASE_WEBSOCKET_API_URL,
        )
        self.order_book_binner = order_book_binner
        self.received_orders = received_orders
        self.matches = matches
        self.received_cancellations = received_cancellations

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
                {"buy": msg["bids"], "sell": msg["asks"]}
            )

        elif msg["type"] == "l2update":
            self.order_book_binner.process_book_changes(msg["changes"])

        elif msg["type"] == "received":
            self.received_orders.append(
                CoinbaseOrder(
                    client_oid=msg.get("client_oid"),
                    funds=msg.get("funds"),
                    order_id=msg["order_id"],
                    order_type=msg["order_type"],
                    order_status=msg["type"],
                    price=msg.get("price"),
                    product_id=msg["product_id"],
                    sequence=msg["sequence"],
                    side=msg["side"],
                    size=msg.get("size"),
                    time=parser.parse(msg["time"]),
                )
            )

        elif msg["type"] == "match":
            self.matches.append(
                CoinbaseMatch(
                    maker_order_id=msg["maker_order_id"],
                    price=msg["price"],
                    product_id=msg["product_id"],
                    sequence=msg["sequence"],
                    side=msg["side"],
                    size=msg["size"],
                    taker_order_id=msg["taker_order_id"],
                    time=parser.parse(msg["time"]),
                    trade_id=msg["trade_id"],
                )
            )

        elif msg["type"] == "done" and msg["reason"] == "canceled":
            self.received_cancellations.append(
                CoinbaseCancellation(
                    order_id=msg["order_id"],
                    price=msg["price"],
                    product_id=msg["product_id"],
                    remaining_size=msg["remaining_size"],
                    side=msg["side"],
                    time=parser.parse(msg["time"]),
                )
            )

        else:
            LOGGER.debug("Error: message type not known: %s", msg)
