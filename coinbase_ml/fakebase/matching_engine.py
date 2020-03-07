"""
matching_engine.py
"""
from datetime import timedelta
from decimal import Decimal
from random import getrandbits, randint
from typing import Dict, List, Optional, Union
from uuid import UUID

import coinbase_ml.fakebase.account as account_module
from .order_book import OrderBook, OrderBookEmpty
from .orm import CoinbaseOrder, CoinbaseMatch, CoinbaseCancellation
from .types import (
    DoneReason,
    Liquidity,
    OrderSide,
    OrderStatus,
    OrderType,
    ProductId,
    ProductPrice,
    ProductVolume,
    QuoteVolume,
)
from .utils.exceptions import OrderNotFoundException


class MatchingEngine:
    """
    Exchange matching engine logic
    """

    def __init__(self, account: Optional[account_module.Account]) -> None:
        """
        __init__ [summary]

        Args:
            account (Optional[account_module.Account]): [description]
        """
        self.account = account
        self.order_book: Dict[OrderSide, OrderBook] = {
            OrderSide.buy: OrderBook(),
            OrderSide.sell: OrderBook(),
        }
        self.matches: List[CoinbaseMatch] = []

    def _add_order_to_order_book(self, order: CoinbaseOrder) -> None:
        """Summary

        Args:
            order (CoinbaseOrder): Description
        """

        # For some reason occasionally orders will be added to
        # the order book twice. This ensure that doesn't happen.
        if order.order_id in self.order_book[order.side].orders_dict:
            return

        if order.order_id in self.account.orders:
            self.account.open_order(order.order_id)
        else:
            order.open_order()

        order_book_key = OrderBook.calculate_order_book_key(order)

        # If two orders for the same price come in at the exact same time add a small
        # random time interval to the newer order until they key is unique
        while order_book_key in self.order_book[order.side]:
            order.time += timedelta(microseconds=randint(-1000, 1000))
            order_book_key = OrderBook.calculate_order_book_key(order)

        self.order_book[order.side].insert(key=order_book_key, value=order)

    def _create_match(
        self,
        filled_volume: ProductVolume,
        maker_order: CoinbaseOrder,
        taker_order: CoinbaseOrder,
    ) -> None:
        """Matches maker_order with taker_order. Creates CoinbaseMatch and adds it self.matches.

        Args:
            filled_volume (ProductVolume): Description
            maker_order (CoinbaseOrder): Description
            taker_order (CoinbaseOrder): Description
        """

        self._process_matched_maker_order(maker_order, taker_order)
        self._process_matched_taker_order(taker_order)

        if taker_order.order_id in self.account.orders:
            liquidity = Liquidity.taker
        elif maker_order.order_id in self.account.orders:
            liquidity = Liquidity.maker
        else:
            liquidity = None

        match = CoinbaseMatch(
            liquidity=liquidity,
            maker_order_id=maker_order.order_id,
            price=maker_order.price,
            product_id=maker_order.get_product_id(),
            side=maker_order.side,
            size=filled_volume,
            taker_order_id=taker_order.order_id,
            time=taker_order.time,
            trade_id=UUID(int=getrandbits(128)).int,
        )

        self.matches.append(match)
        maker_order.matches.append(match)
        taker_order.matches.append(match)

        if maker_order.order_id in self.account.orders:
            self.account.process_match(match, maker_order.order_id)

        if taker_order.order_id in self.account.orders:
            self.account.process_match(match, taker_order.order_id)

    def _check_for_self_trade(
        self, maker_order: CoinbaseOrder, taker_order: CoinbaseOrder
    ) -> bool:
        """If both maker_order and taker_order are in self.acount.orders dict
        will return True else False.

        Args:
            maker_order (CoinbaseOrder): Description
            taker_order (CoinbaseOrder): Description

        Returns:
            bool: Description
        """
        return (
            maker_order.order_id in self.account.orders
            and taker_order.order_id in self.account.orders
        )

    @staticmethod
    def _deincrement_matched_order_sizes(
        maker_order: CoinbaseOrder, taker_order: CoinbaseOrder
    ) -> ProductVolume:
        """Simultaneously deincrement the remaining_size of a
        matched maker and taker order. Used if taker_order is not
        a buy side market order.

        Args:
            maker_order (CoinbaseOrder): Description
            taker_order (CoinbaseOrder): Description

        Returns:
            ProductVolume: filled_volume
        """
        filled_volume = min(maker_order.remaining_size, taker_order.remaining_size)

        maker_order.remaining_size, taker_order.remaining_size = (
            max(
                taker_order.get_product_id().product_volume_type.get_zero_volume(),
                maker_order.remaining_size - taker_order.remaining_size,
            ),
            max(
                taker_order.get_product_id().product_volume_type.get_zero_volume(),
                taker_order.remaining_size - maker_order.remaining_size,
            ),
        )

        return filled_volume

    @staticmethod
    def _deincrement_market_order_funds_and_maker_order_size(
        maker_order: CoinbaseOrder, taker_order: CoinbaseOrder
    ) -> ProductVolume:
        """_deincrement_market_order_funds_and_maker_order_size will
        deincrement both the funds of the taker_order and the size of the
        maker_order. Used when taker_order is a buy side market order.

        Args:
            maker_order (CoinbaseOrder): Description
            taker_order (CoinbaseOrder): Description

        Returns:
            ProductVolume: filled_volume
        """
        taker_order_desired_volume = taker_order.get_product_id().product_volume_type(
            taker_order.funds.amount / maker_order.price.amount
        )

        filled_volume = min(taker_order_desired_volume, maker_order.remaining_size)

        taker_order.funds, maker_order.remaining_size = (
            max(
                taker_order.get_product_id().quote_volume_type.get_zero_volume(),
                taker_order.funds - maker_order.price * filled_volume,
            ),
            max(
                taker_order.get_product_id().product_volume_type.get_zero_volume(),
                maker_order.remaining_size - filled_volume,
            ),
        )

        return filled_volume

    def _get_best_maker_order(self, taker_order_side: OrderSide) -> CoinbaseOrder:
        """
        _get_best_maker_order returns the best maker order on the order book.
        This is the min element on the sell order book if taker_order_side is buy,
        and it is the max element on the buy order book if taker_order_side is sell.

        Args:
            taker_order_side (OrderSide): [description]

        Returns:
            CoinbaseOrder: [description]
        """
        _, maker_order = (
            self.order_book[OrderSide.sell].min_item()
            if taker_order_side == OrderSide.buy
            else self.order_book[OrderSide.buy].max_item()
        )

        return maker_order

    def _get_max_buy_price(self, product_id: ProductId) -> ProductPrice:
        """
        _get_max_buy_price returns maximum buy price on the order book.
        Used by the exchange to determine which open buy order is
        matched first.

        Args:
            product_id (ProductId): [description]

        Returns:
            ProductPrice: [description]
        """
        return self.order_book[OrderSide.buy].max_key_or_default(
            default_value=(product_id.zero_price, timedelta.min)
        )[0]

    def _get_min_sell_price(self, product_id: ProductId) -> ProductPrice:
        """
        _get_min_sell_price returns minimum sell price on the order book.
        Used by the exchange to determine which open sell
        order is matched first.

        Args:
            product_id (ProductId): [description]

        Returns:
            ProductPrice: [description]
        """
        return self.order_book[OrderSide.sell].min_key_or_default(
            default_value=(product_id.max_price, timedelta.max)
        )[0]

    def _match_market_order(  # pylint: disable=R0912
        self, market_order: CoinbaseOrder
    ) -> None:
        """If market_order side is 'buy' use funds to purchase product until funds are depleted or
        no more product is for sale. If market_order side is 'sell' sell specified amount.

        Args:
            market_order (CoinbaseOrder): Description
        """
        slippage_protection = SlippageProtection()

        zero_volume: Union[ProductVolume, QuoteVolume]
        if market_order.funds is not None and market_order.size is None:
            deincrement_op = self._deincrement_market_order_funds_and_maker_order_size
            get_loop_quantity = lambda market_order: market_order.funds
            zero_volume = (
                market_order.get_product_id().quote_volume_type.get_zero_volume()
            )
        elif market_order.funds is None and market_order.size is not None:
            deincrement_op = self._deincrement_matched_order_sizes
            get_loop_quantity = lambda market_order: market_order.remaining_size
            zero_volume = (
                market_order.get_product_id().product_volume_type.get_zero_volume()
            )
        else:
            # TODO: it seems like a small amount of market orders have both funds and size
            # implemented. Investigate.
            return

        while get_loop_quantity(market_order) > zero_volume:
            try:
                maker_order = self._get_best_maker_order(market_order.side)
            except OrderBookEmpty:
                self.cancel_order(market_order)
                break

            if self._check_for_self_trade(maker_order, market_order):
                self.cancel_order(market_order)
                break

            if slippage_protection.check_slippage_is_greater_than_max(
                maker_order.price
            ):
                self.cancel_order(market_order)
                break

            filled_volume = deincrement_op(maker_order, market_order)

            self._create_match(filled_volume, maker_order, market_order)

            self._pop_maker_order_if_filled(maker_order)

    def _match_limit_order(self, taker_order: CoinbaseOrder) -> None:
        """Matches taker limit order with highest priority maker order

        Args:
            taker_order (CoinbaseOrder): Description
        """
        slippage_protection = SlippageProtection()
        zero_volume = taker_order.get_product_id().product_volume_type.get_zero_volume()
        while taker_order.remaining_size > zero_volume:
            try:
                maker_order = self._get_best_maker_order(taker_order.side)
            except OrderBookEmpty:
                self._add_order_to_order_book(taker_order)
                break

            if self._check_for_self_trade(maker_order, taker_order):
                self.cancel_order(taker_order)
                break

            if slippage_protection.check_slippage_is_greater_than_max(
                maker_order.price
            ):
                self.cancel_order(taker_order)
                break

            filled_volume = self._deincrement_matched_order_sizes(
                maker_order, taker_order
            )

            self._create_match(filled_volume, maker_order, taker_order)

            self._pop_maker_order_if_filled(maker_order)

    def _pop_maker_order_if_filled(
        self, maker_order: CoinbaseOrder
    ) -> Optional[CoinbaseOrder]:
        """
        _pop_maker_order_if_filled will pop a maker order
        from the order book if it has remaining_size=0.
        Used by the _match_limit_order and _match_market_order
        functions to remove orders from the order book when they've
        been completely filled by taker orders.

        Args:
            maker_order (CoinbaseOrder): [description]

        Returns:
            Optional[CoinbaseOrder]: [description]
        """
        return (
            self.order_book[maker_order.side].pop_by_order_id(maker_order.order_id)
            if maker_order.remaining_size.is_zero()
            else None
        )

    def _process_matched_maker_order(
        self, maker_order: CoinbaseOrder, taker_order: CoinbaseOrder
    ) -> None:
        """
        _process_matched_maker_order [summary]

        Args:
            maker_order (CoinbaseOrder): [description]
            taker_order (CoinbaseOrder): [description]
        """
        if maker_order.remaining_size.is_zero():
            if maker_order.order_id in self.account.orders:
                self.account.close_order(maker_order.order_id, taker_order.time)
            else:
                maker_order.close_order(
                    done_at=taker_order.time, done_reason=DoneReason.filled
                )

    def _process_matched_taker_order(self, taker_order: CoinbaseOrder) -> None:
        """
        _process_matched_taker_order [summary]

        Args:
            taker_order (CoinbaseOrder): [description]

        Raises:
            AttributeError: [description]
        """
        if taker_order.remaining_size:
            predicate = taker_order.remaining_size.is_zero()
        elif taker_order.funds:
            predicate = taker_order.funds.is_zero()
        else:
            raise AttributeError(
                "taker_order must have either funds or remaining_size defined"
            )

        if predicate:
            if taker_order.order_id in self.account.orders:
                # Open the order before closing it, so that the holds on the funds
                # are update correctly
                self.account.open_order(taker_order.order_id)
                self.account.close_order(taker_order.order_id, taker_order.time)
            else:
                taker_order.close_order(
                    done_at=taker_order.time, done_reason=DoneReason.filled
                )

    def cancel_order(self, order: CoinbaseOrder) -> None:
        """Cancel open order or received order

        Args:
            order (CoinbaseOrder): Description

        Raises:
            OrderNotFoundException: Description
        """
        if order.order_status == OrderStatus.open:
            if order.order_id in self.order_book[order.side].orders_dict:
                order = self.order_book[order.side].pop_by_order_id(order.order_id)
            else:
                raise OrderNotFoundException(order.order_id)

        elif order.order_status == OrderStatus.received:
            pass
            # We don't have to do anything special for this case.
            # Just don't raise a ValueError.

        else:
            raise ValueError("Can only cancel 'open' or 'received' order.")

        # If order is account we maintain let the account handle cancelation logic
        # else just cancel the order.
        if order.order_id in self.account.orders:
            self.account.cancel_order(order.order_id)
        else:
            order.close_order(
                done_at=self.account.exchange.interval_start_dt,
                done_reason=DoneReason.cancelled,
            )

    def check_is_taker(self, order: CoinbaseOrder) -> bool:
        """Summary

        Args:
            order (CoinbaseOrder): Description

        Returns:
            bool: Description
        """
        return (
            order.side == OrderSide.buy
            and order.price >= self._get_min_sell_price(order.get_product_id())
        ) or (
            order.side == OrderSide.sell
            and order.price <= self._get_max_buy_price(order.get_product_id())
        )

    def process_cancellation(self, cancellation_event: CoinbaseCancellation) -> None:
        """
        _process_cancellation [summary]

        Args:
            cancellation_event (CoinbaseCancellation): [description]

        Returns:
            None: [description]
        """
        if (
            cancellation_event.order_id
            in self.order_book[cancellation_event.side].orders_dict
        ):
            order = self.order_book[cancellation_event.side].orders_dict[
                cancellation_event.order_id
            ]
            self.cancel_order(order)
        else:
            # Sometimes we'll get cancellations for orders not on the order book
            # There's nothing we can do with these, so ignore.
            pass

    def process_order(self, order_event: CoinbaseOrder) -> None:
        """
        _process_order [summary]

        Args:
            order_event (CoinbaseOrder): [description]

        Returns:
            None: [description]
        """
        if order_event.order_type == OrderType.limit:
            predicate = (
                order_event.price
                < self._get_min_sell_price(order_event.get_product_id())
                if order_event.side == OrderSide.buy
                else order_event.price
                > self._get_max_buy_price(order_event.get_product_id())
            )

            if predicate:
                self._add_order_to_order_book(order_event)
            else:
                self._match_limit_order(order_event)

        elif order_event.order_type == OrderType.market:
            self._match_market_order(order_event)

        else:
            raise ValueError(f"Order type {order_event.order_type} not recognized.")


class SlippageProtection:
    """
    SlippageProtection is a utility to class used to cancel
    taker orders if matching them would result in greater than
    slippage of MAX_PRICE_SLIPPAGE_POINTS
    """

    MAX_PRICE_SLIPPAGE_POINTS = Decimal("0.1")

    def __init__(self) -> None:
        """
        __init__ [summary]
        """
        self.price_slippage_points = Decimal(0.0)
        self.first_match_price: Optional[ProductPrice] = None

    def check_slippage_is_greater_than_max(
        self, maker_order_price: ProductPrice
    ) -> bool:
        """
        check_slippage_is_greater_than_max [summary]

        Args:
            maker_order_price (ProductPrice): [description]

        Returns:
            bool: [description]
        """
        if self.first_match_price is None:
            self.first_match_price = maker_order_price
        else:
            self.price_slippage_points = (
                abs(maker_order_price.amount - self.first_match_price.amount)
                / self.first_match_price.amount
            )

        return self.price_slippage_points > SlippageProtection.MAX_PRICE_SLIPPAGE_POINTS
