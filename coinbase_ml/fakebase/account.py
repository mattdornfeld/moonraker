"""Account object for interacting with Exchange object
"""
from __future__ import annotations
from copy import deepcopy
from datetime import datetime, timedelta
from random import getrandbits, uniform
from typing import TYPE_CHECKING, Any, Dict, List, Optional, Union, cast
from uuid import UUID

from .base_classes.account import AccountBase, Funds
from .orm import CoinbaseMatch, CoinbaseOrder
from .utils import generate_order_id
from .utils.exceptions import OrderNotFoundException
from .types import (
    Currency,
    DoneReason,
    InvalidTypeError,
    Liquidity,
    OrderId,
    OrderSide,
    OrderStatus,
    OrderType,
    ProductId,
    ProductPrice,
    ProductVolume,
    QuoteVolume,
    RejectReason,
    Volume,
)

if TYPE_CHECKING:
    # To avoid circular import exchange on the submodule level
    # Exchange is then referenced using the str syntax below
    import coinbase_ml.fakebase.exchange as exchange


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
        self.profile_id = str(UUID(int=getrandbits(128)))
        self._funds: Dict[Currency, Funds] = {}
        self._orders: Dict[OrderId, CoinbaseOrder] = {}

        for currency in self.currencies:
            self._funds[currency] = Funds(
                id=str(UUID(int=getrandbits(128))),
                balance=currency.zero_volume,
                currency=currency,
                holds=currency.zero_volume,
            )

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
                self.profile_id == _other.profile_id
                and self.funds == _other.funds
                and self._orders == _other.orders
            )
        else:
            raise InvalidTypeError(type(other), "other")

        return return_val

    def _add_order(self, order: CoinbaseOrder) -> None:
        """Summary

        Args:
            order (CoinbaseOrder): Description
        """
        if order.side == OrderSide.buy:

            quote_volume = self.calc_buy_hold(order)

            quote_funds = cast(
                QuoteVolume, self.get_available_funds(order.quote_currency)
            )
            if quote_volume > quote_funds:

                order.reject_order(reject_reason=RejectReason.insufficient_funds)

            if order.order_status == OrderStatus.received:
                self._funds[order.quote_currency].holds += quote_volume

        else:

            product_funds = cast(
                ProductVolume, self.get_available_funds(order.product_currency)
            )
            if order.size > product_funds:

                order.reject_order(reject_reason=RejectReason.insufficient_funds)

            if order.order_status == OrderStatus.received:
                self._funds[order.product_currency].holds += order.size

        self._orders[order.order_id] = order

    @staticmethod
    def _calc_purchasing_funds(order: CoinbaseOrder) -> QuoteVolume:
        """
        _calc_purchasing_funds returns funds that will be used to purchase product

        Args:
            order (CoinbaseOrder): [description]

        Returns:
            QuoteVolume: [description]
        """
        return (
            order.price * order.size
            if order.order_type == OrderType.limit
            else order.original_funds
        )

    def _remove_holds(self, order: CoinbaseOrder) -> None:
        """Summary

        Args:
            order (CoinbaseOrder): Description
        """
        if order.side == OrderSide.buy and order.order_status == OrderStatus.open:
            self._funds[order.quote_currency].holds -= self._calc_purchasing_funds(
                order
            )
        elif order.side == OrderSide.buy and order.order_status == OrderStatus.received:
            self._funds[order.quote_currency].holds -= self.calc_buy_hold(order)
        elif order.side == OrderSide.sell:
            self._funds[order.product_currency].holds -= order.size
        else:
            raise ValueError(
                "Cannot call _remove_holds for an order with "
                f"side = {order.side} and order_status = {order.order_status}"
            )

    def add_funds(self, currency: Currency, amount: Volume) -> None:
        """Summary

        Args:
            currency (Currency): Description
            amount (Volume): Description
        """
        self._funds[currency].balance += currency.volume_type(amount.amount)

    def process_match(self, match: CoinbaseMatch, order_id: OrderId) -> None:
        """
        process_match [summary]

        Args:
            match (CoinbaseMatch): [description]
            order_id (OrderId): [description]

        Raises:
            OrderNotFoundException: [description]
        """
        if order_id not in self._orders:
            raise OrderNotFoundException

        order_side = match.account_order_side

        if order_side == OrderSide.buy:
            self._funds[match.quote_currency].balance -= (
                match.price * match.size + match.fee
            )
            self._funds[match.product_currency].balance += match.size
        else:
            self._funds[match.quote_currency].balance += (
                match.price * match.size - match.fee
            )
            self._funds[match.product_currency].balance -= match.size

    @staticmethod
    def calc_buy_hold(order: CoinbaseOrder) -> QuoteVolume:
        """
        calc_buy_hold [summary]

        Args:
            order (CoinbaseOrder): [description]

        Returns:
            QuoteVolume: [description]
        """
        return Account._calc_purchasing_funds(order) * (
            1 + Liquidity.taker.fee_fraction
        )

    def cancel_order(self, order_id: OrderId) -> CoinbaseOrder:
        """Summary

        Args:
            order_id (OrderId): Description

        Raises:
            ValueError: Description

        Returns:
            CoinbaseOrder: Description
        """
        if self._orders[order_id].order_status in [
            OrderStatus.open,
            OrderStatus.received,
        ]:
            order = self._orders.pop(order_id)
            self._remove_holds(order)
            if order.order_id in self.exchange.order_book[order.side].orders_dict:
                self.exchange.cancel_order(order)
        else:
            raise ValueError("Can only cancel 'open' or 'received' order.")

        order.close_order(
            done_at=self.exchange.interval_start_dt, done_reason=DoneReason.cancelled
        )

        return order

    def close_order(self, order_id: OrderId, time: datetime) -> None:
        """Summary

        Args:
            order_id (OrderId): Description
            time (datetime): Description

        Raises:
            ValueError: Description
        """
        order = self._orders[order_id]

        if order.order_status not in [OrderStatus.open, OrderStatus.received]:
            raise ValueError("Can only close open or received order.")

        self._remove_holds(order)
        order.close_order(done_at=time, done_reason=DoneReason.filled)

    def copy(self) -> Account:
        """
        copy returns a deepcopy of the Account object without no
        connection to an exchange. Mostly used by the ExchangeCheckpoint
        class.

        Returns:
            Account: [description]
        """
        exchange = self.exchange
        self.exchange = None
        copied_account = deepcopy(self)
        self.exchange = exchange

        return copied_account

    @property
    def funds(self) -> Dict[Currency, Funds]:
        return self._funds

    def get_available_funds(
        self, currency: Currency
    ) -> Union[ProductVolume, QuoteVolume]:
        """
        get_available_funds [summary]

        Args:
            currency (Currency): [description]

        Returns:
            Union[ProductVolume, QuoteVolume]: [description]
        """
        return self.funds[currency].balance - self.funds[currency].holds

    def open_order(self, order_id: OrderId) -> None:
        """Summary

        Args:
            order_id (OrderId): Description
        """
        order = self._orders[order_id]

        if order.side == OrderSide.buy:
            self._funds[order.quote_currency].holds -= (
                self._calc_purchasing_funds(order) * Liquidity.taker.fee_fraction
            )

        order.open_order()

    @property
    def orders(self) -> Dict[OrderId, CoinbaseOrder]:
        return self._orders

    def get_accounts(self) -> Dict[str, Dict[str, str]]:
        """
        get_accounts [summary]

        Returns:
            Dict[str, Dict[str, str]]: [description]
        """
        return {account["currency"]: account for account in self.to_dict()}

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
        # include random offset so that orders place in the same step aren't placed
        # at the exact same time
        time = self.exchange.interval_end_dt + timedelta(seconds=uniform(-0.01, 0.01))

        order = CoinbaseOrder(
            order_id=generate_order_id(),
            order_type=OrderType.limit,
            order_status=OrderStatus.received,
            post_only=post_only,
            price=price,
            product_id=product_id,
            side=side,
            size=size,
            time=time,
            time_in_force=time_in_force,
            time_to_live=time_to_live,
        )

        # The order has to be created before the exchange can check to see if it is
        # a taker or a maker. So we set this property post init.
        order.is_taker = self.exchange.check_is_taker(order)
        order.reject_order_if_invalid()
        self._add_order(order)

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
        if side == OrderSide.buy:
            if funds is None or size is not None:
                raise ValueError("Must specify funds and not size for buy orders.")
        else:
            if funds is not None or size is None:
                raise ValueError("Must specify size and not funds for sell orders.")

        # include random offset so that orders place in the same step aren't placed
        # at the exact same time
        time = self.exchange.interval_end_dt + timedelta(seconds=uniform(-0.01, 0.01))

        order = CoinbaseOrder(
            funds=funds,
            order_id=generate_order_id(),
            order_type=OrderType.market,
            order_status=OrderStatus.received,
            product_id=product_id,
            side=side,
            size=size,
            time=time,
        )

        order.is_taker = True
        order.reject_order_if_invalid()
        self._add_order(order)

        return order

    def to_dict(self) -> List[Dict[str, str]]:
        """Summary

        Returns:
            List[Dict[str, str]]: Description
        """
        funds_dicts = []
        for currency, fund in self.funds.items():

            balance = fund.balance
            holds = fund.holds
            available = balance - holds

            funds_dicts.append(
                {
                    "available": str(available),
                    "balance": str(balance),
                    "currency": currency.value,
                    "holds": str(holds),
                    "id": str(fund.id),
                    "profile_id": self.profile_id,
                }
            )

        return funds_dicts
