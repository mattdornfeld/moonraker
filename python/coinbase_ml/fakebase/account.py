"""Account object for interacting with Exchange object
"""
from __future__ import annotations

from datetime import timedelta
from typing import TYPE_CHECKING, Dict, List, Optional

from dateutil import parser
from google.protobuf.duration_pb2 import Duration
from grpc import Channel

import coinbase_ml.common.constants as cc
from coinbase_ml.common.protos.events_pb2 import SimulationId as SimulationIdProto
from coinbase_ml.common.types import SimulationId
from coinbase_ml.fakebase.base_classes.account import Funds
from coinbase_ml.fakebase.orm import CoinbaseMatch, CoinbaseOrder
from coinbase_ml.fakebase.protos.events_pb2 import (
    Cancellation,
    BuyLimitOrder,
    BuyMarketOrder,
    MatchEvents,
    Orders,
    SellLimitOrder,
    SellMarketOrder,
)
from coinbase_ml.fakebase.protos.fakebase_pb2 import (
    AccountInfo,
    CancellationRequest,
    BuyLimitOrderRequest,
    BuyMarketOrderRequest,
    SellLimitOrderRequest,
    SellMarketOrderRequest,
    Wallets,
)
from coinbase_ml.fakebase.protos.fakebase_pb2_grpc import AccountServiceStub
from coinbase_ml.fakebase.types import (
    Currency,
    OrderId,
    OrderSide,
    OrderStatus,
    OrderType,
    ProductId,
    ProductPrice,
    ProductVolume,
    QuoteVolume,
)
from coinbase_ml.fakebase.utils.proto_utils import order_from_sealed_value

if TYPE_CHECKING:
    # To avoid circular import exchange on the submodule level
    # Exchange is then referenced using the str syntax below
    pass


class Account:
    def __init__(
        self, channel: Channel, account_info: AccountInfo, simulation_id: SimulationId
    ):
        self.account_info = account_info
        self.simulation_id = simulation_id
        self.stub = AccountServiceStub(channel)
        self._simulation_id_proto = SimulationIdProto(simulationId=simulation_id)

    def cancel_order(self, order_id: OrderId) -> CoinbaseOrder:
        cancellation: Cancellation = self.stub.cancelOrder(
            CancellationRequest(
                orderId=order_id, simulationId=self._simulation_id_proto
            )
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
        wallets: Wallets = self.stub.getWallets(
            self._simulation_id_proto
        ) if self.account_info is None else self.account_info.wallets

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
        match_events: MatchEvents = self.stub.getMatches(
            self._simulation_id_proto
        ) if self.account_info is None else self.account_info.matchEvents
        return [CoinbaseMatch.from_proto(match) for match in match_events.matchEvents]

    @property
    def orders(self) -> Dict[OrderId, CoinbaseOrder]:
        orders_proto: Orders = self.stub.getOrders(self._simulation_id_proto)

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
        time_to_live: timedelta = timedelta.max,
    ) -> CoinbaseOrder:
        _time_to_live = Duration()
        _time_to_live.FromTimedelta(time_to_live)  # pylint: disable=no-member

        if side is OrderSide.buy:
            buy_order_proto: BuyLimitOrder = self.stub.placeBuyLimitOrder(
                BuyLimitOrderRequest(
                    price=str(price),
                    productId=str(product_id),
                    size=str(size),
                    postOnly=post_only,
                    timeToLive=_time_to_live,
                    simulationId=self._simulation_id_proto,
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
                    timeToLive=_time_to_live,
                    simulationId=self._simulation_id_proto,
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
        if side is OrderSide.buy:
            if funds is None or size is not None:
                raise ValueError("Must specify funds and not size for buy orders.")
        else:
            if funds is not None or size is None:
                raise ValueError("Must specify size and not funds for sell orders.")

        if side is OrderSide.buy:
            buy_order_proto: BuyMarketOrder = self.stub.placeBuyMarketOrder(
                BuyMarketOrderRequest(
                    funds=str(funds),
                    productId=str(product_id),
                    simulationId=self._simulation_id_proto,
                )
            )

            order = CoinbaseOrder.from_proto(buy_order_proto)
        else:
            sell_order_proto: SellMarketOrder = self.stub.placeSellMarketOrder(
                SellMarketOrderRequest(
                    size=str(size),
                    productId=str(product_id),
                    simulationId=self._simulation_id_proto,
                )
            )

            order = CoinbaseOrder.from_proto(sell_order_proto)

        return order
