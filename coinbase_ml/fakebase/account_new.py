"""Account object for interacting with Exchange object
"""
from __future__ import annotations
from datetime import timedelta
from typing import TYPE_CHECKING, Any, Dict, List, Optional

from dateutil import parser
from google.protobuf.empty_pb2 import Empty

import coinbase_ml.common.constants as cc
import coinbase_ml.fakebase.constants as c
from coinbase_ml.fakebase.base_classes.account import AccountBase, Funds
from coinbase_ml.fakebase.orm import CoinbaseMatch, CoinbaseOrder
from coinbase_ml.fakebase.protos.fakebase_pb2 import (
    Cancellation,
    CancellationRequest,
    BuyLimitOrder,
    BuyLimitOrderRequest,
    BuyMarketOrderRequest,
    BuyMarketOrder,
    MatchEvents,
    Orders,
    SellLimitOrder,
    SellLimitOrderRequest,
    SellMarketOrder,
    SellMarketOrderRequest,
    Wallets,
)
from coinbase_ml.fakebase.protos.fakebase_pb2_grpc import AccountServiceStub
from coinbase_ml.fakebase.utils.proto_utils import order_from_sealed_value
from coinbase_ml.fakebase.types import (
    Currency,
    InvalidTypeError,
    OrderId,
    OrderSide,
    OrderStatus,
    OrderType,
    ProductId,
    ProductPrice,
    ProductVolume,
    QuoteVolume,
)

if TYPE_CHECKING:
    # To avoid circular import exchange on the submodule level
    # Exchange is then referenced using the str syntax below
    import coinbase_ml.fakebase.exchange_new as exchange


class Account(AccountBase["exchange.Exchange"]):

    """Summary

    Attributes:
        funds (dict[str, dict]): Description
        orders (dict[str, CoinbaseOrder]): Description
        profile_id (str): Description
    """

    def __init__(self, exchange: "exchange.Exchange"):
        """
        __init__ [summary]

        Args:
            exchange (exchange.Exchange): [description]
        """
        super().__init__(exchange)

        self.stub = AccountServiceStub(c.MATCHING_ENGINE_CHANNEL)

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ [summary]

        Args:
            other (Any): [description]

        Returns:
            bool: [description]
        """
        if isinstance(other, Account):
            _other: Account = other
            return_val = (
                self.funds == _other.funds
                and self.orders == _other.orders
                and self.matches == _other.matches
            )
        else:
            raise InvalidTypeError(type(other), "other")

        return return_val

    def cancel_order(self, order_id: OrderId) -> CoinbaseOrder:
        """
        cancel_order [summary]

        Args:
            order_id (OrderId): [description]

        Returns:
            CoinbaseOrder: [description]
        """
        cancellation: Cancellation = self.stub.cancelOrder(
            CancellationRequest(orderId=order_id)
        )

        return CoinbaseOrder(
            order_id=OrderId(cancellation.orderId),
            order_status=OrderStatus.done,
            order_type=OrderType.limit,
            product_id=cc.PRODUCT_ID,
            side=OrderSide.from_proto(cancellation.side),
            time=parser.parse(cancellation.time),
        )

    @property
    def funds(self) -> Dict[Currency, Funds]:
        wallets: Wallets = self.stub.getWallets(Empty())

        _funds: Dict[Currency, Funds] = {}
        for (_currency, wallet) in wallets.wallets.items():
            currency = Currency.from_string(_currency)

            _funds[currency] = Funds(
                currency=currency,
                id=wallet.id,
                balance=currency.volume_type(wallet.balance),
                holds=currency.volume_type(wallet.holds),
            )

        return _funds

    @property
    def matches(self) -> List[CoinbaseMatch]:
        """
        matches [summary]

        Returns:
            List[CoinbaseMatch]: [description]
        """
        match_events: MatchEvents = self.stub.getMatches(Empty())
        return [CoinbaseMatch.from_proto(match) for match in match_events.matchEvents]

    @property
    def orders(self) -> Dict[OrderId, CoinbaseOrder]:
        """
        orders [summary]

        Returns:
            Dict[OrderId, CoinbaseOrder]: [description]
        """
        orders_proto: Orders = self.stub.getOrders(Empty())

        return {
            OrderId(order_id): order_from_sealed_value(order)
            for (order_id, order) in orders_proto.orders.items()
        }

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

        Returns:
            CoinbaseOrder: [description]
        """
        if side is OrderSide.buy:
            buy_order_proto: BuyLimitOrder = self.stub.placeBuyLimitOrder(
                BuyLimitOrderRequest(
                    price=str(price),
                    productId=str(product_id),
                    size=str(size),
                    postOnly=post_only,
                )
            )

            order = CoinbaseOrder.from_proto(buy_order_proto)
        else:
            sell_order_proto: SellLimitOrder = self.stub.placeSellLimitOrder(
                SellLimitOrderRequest(
                    price=str(price),
                    productId=str(product_id),
                    size=str(size),
                    postOnly=post_only,
                )
            )

            order = CoinbaseOrder.from_proto(sell_order_proto)

        return order

    def place_market_order(
        self,
        product_id: ProductId,
        side: OrderSide,
        funds: Optional[QuoteVolume] = None,
        size: Optional[ProductVolume] = None,
    ) -> CoinbaseOrder:
        """
        place_market_order [summary]

        Args:
            product_id (ProductId): [description]
            side (OrderSide): [description]
            funds (Optional[QuoteVolume], optional): [description]. Defaults to None.
            size (Optional[ProductVolume], optional): [description]. Defaults to None.

        Raises:
            ValueError: [description]
            ValueError: [description]

        Returns:
            CoinbaseOrder: [description]
        """
        if side is OrderSide.buy:
            if funds is None or size is not None:
                raise ValueError("Must specify funds and not size for buy orders.")
        else:
            if funds is not None or size is None:
                raise ValueError("Must specify size and not funds for sell orders.")

        if side is OrderSide.buy:
            buy_order_proto: BuyMarketOrder = self.stub.placeBuyMarketOrder(
                BuyMarketOrderRequest(funds=str(funds), productId=str(product_id))
            )

            order = CoinbaseOrder.from_proto(buy_order_proto)
        else:
            sell_order_proto: SellMarketOrder = self.stub.placeSellMarketOrder(
                SellMarketOrderRequest(size=str(size), productId=str(product_id))
            )

            order = CoinbaseOrder.from_proto(sell_order_proto)

        return order
