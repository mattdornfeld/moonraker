"""
 [summary]
"""
from datetime import datetime

import numpy as np
import pytest
from pytest_cases import unpack_fixture

from coinbase_ml.fakebase.account import Account
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.fakebase.orm import CoinbaseOrder
from coinbase_ml.fakebase.matching_engine import SlippageProtection
from coinbase_ml.fakebase.types import (
    Currency,
    DoneReason,
    OrderSide,
    OrderStatus,
    OrderType,
    Price,
    RejectReason,
    Volume,
)
from coinbase_ml.fakebase.utils import generate_order_id
from coinbase_ml.fakebase.utils.exceptions import OrderNotFoundException

from .. import constants as tc
from ..fixtures import create_exchange

(  # pylint: disable=unbalanced-tuple-unpacking
    account,  # pylint: disable=invalid-name
    exchange,  # pylint: disable=invalid-name
) = unpack_fixture("account,exchange", create_exchange)

# pylint: disable=redefined-outer-name


class TestExchangeUnit:
    """
    unit tests for exchange.py
    """

    @staticmethod
    def test_account_equality(account: Account, exchange: Exchange) -> None:
        """
        test_account_equality [summary]

        Args:
            account (Account): [description]
            exchange (Exchange): [description]
        """
        assert account == exchange.account
        assert account == exchange.matching_engine.account
        assert exchange.account == exchange.matching_engine.account

    @staticmethod
    def test_add_account(account: Account, exchange: Exchange) -> None:
        """
        test_add_account [summary]

        Args:
            account (Account): [description]
            exchange (Exchange): [description]

        Returns:
            None: [description]
        """
        assert exchange.account == account

    @staticmethod
    def test_cancel_order(account: Account, exchange: Exchange) -> None:
        """
        test_cancel_order [summary]

        Args:
            account (Account): [description]
            exchange (Exchange): [description]

        Returns:
            None: [description]
        """
        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=tc.TEST_ORDER_SIDE,
            size=tc.TEST_ORDER_SIZE,
        )

        exchange.step()
        assert exchange.order_book[order.side].contains_order_id(order.order_id)
        exchange.cancel_order(order)
        assert not exchange.order_book[order.side].contains_order_id(order.order_id)

    @staticmethod
    def test_cancel_order_raise_exception(exchange: Exchange) -> None:
        """
        test_cancel_order_raise_exception [summary]

        Args:
            exchange (Exchange): [description]

        Returns:
            None: [description]
        """
        with pytest.raises(OrderNotFoundException):
            exchange.cancel_order(
                CoinbaseOrder(order_status=OrderStatus.open, side=tc.TEST_ORDER_SIDE)
            )

    @staticmethod
    def test_create_restore_checkpoint(exchange: Exchange) -> None:
        """
        test_create_restore_checkpoint [summary]

        Args:
            exchange (Exchange): [description]

        Returns:
            None: [description]
        """ """
        test_create_restore_checkpoint [summary]
        """
        restored_exchange = exchange.create_checkpoint().restore()

        assert exchange == restored_exchange

    @staticmethod
    def test_exchange_equality(account: Account, exchange: Exchange) -> None:
        """
        test_exchange_equality [summary]

        Args:
            account (Account): [description]
            exchange (Exchange): [description]
        """
        assert exchange == account.exchange

    @staticmethod
    def test_finished_false(exchange: Exchange) -> None:
        """
        test_finished_false [summary]

        Args:
            exchange (Exchange): [description]

        Returns:
            None: [description]
        """
        assert exchange.finished is False

    @staticmethod
    def test_finished_true(exchange: Exchange) -> None:
        """
        test_finished_true [summary]

        Args:
            exchange (Exchange): [description]

        Returns:
            None: [description]
        """
        exchange.interval_end_dt = datetime.max
        assert exchange.finished is True

    @staticmethod
    @pytest.mark.parametrize(
        "price, size, reject_reason",
        [
            (
                tc.PRODUCT_ID.price_type("1e-3"),
                tc.TEST_ORDER_SIZE,
                RejectReason.price_too_small,
            ),
            (
                tc.PRODUCT_ID.price_type("1e11"),
                tc.TEST_ORDER_SIZE,
                RejectReason.insufficient_funds,
            ),
            (
                tc.PRODUCT_ID.price_type("1e11"),
                tc.TEST_ORDER_SIZE,
                RejectReason.price_too_large,
            ),
            (
                tc.TEST_ORDER_PRICE,
                tc.PRODUCT_ID.quote_volume_type("1e-4"),
                RejectReason.size_too_small,
            ),
            (
                tc.TEST_ORDER_PRICE,
                tc.PRODUCT_ID.quote_volume_type("1e5"),
                RejectReason.size_too_large,
            ),
        ],
    )
    def test_invalid_order(
        account: Account, price: Price, size: Volume, reject_reason: RejectReason
    ) -> None:
        """
        test_invalid_order [summary]

        Args:
            account (Account): [description]
            price (Price): [description]
            size (Volume): [description]
            reject_reason (RejectReason): [description]
        """
        quote_funds = tc.TEST_WALLET_QUOTE_FUNDS
        if reject_reason in [RejectReason.price_too_large, RejectReason.size_too_large]:
            additional_funds = tc.PRODUCT_ID.quote_volume_type("1e20")
            quote_funds += additional_funds
            account.add_funds(Currency.USD, additional_funds)

        invalid_order = account.place_limit_order(
            product_id=tc.PRODUCT_ID, price=price, side=tc.TEST_ORDER_SIDE, size=size
        )

        assert account.funds[tc.QUOTE_CURRENCY].balance == quote_funds
        assert account.funds[tc.QUOTE_CURRENCY].holds.is_zero()
        assert (
            account.funds[tc.PRODUCT_CURRENCY].balance == tc.TEST_WALLET_PRODUCT_FUNDS
        )
        assert account.funds[tc.PRODUCT_CURRENCY].holds.is_zero()

        assert invalid_order.reject_reason == reject_reason

    @staticmethod
    def test_place_limit_order(account: Account, exchange: Exchange) -> None:
        """
        test_place_limit_order [summary]

        Args:
            account (Account): [description]
            exchange (Exchange): [description]
        """
        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=tc.TEST_ORDER_SIDE,
            size=tc.TEST_ORDER_SIZE,
        )

        exchange.step()

        assert exchange.order_book[order.side].contains_order_id(order.order_id)

    @staticmethod
    def test_self_trade_prevention(account: Account, exchange: Exchange) -> None:
        """
        test_self_trade_prevention [summary]

        Args:
            account (Account): [description]
            exchange (Exchange): [description]
        """
        buy_order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=tc.TEST_ORDER_SIDE,
            size=tc.TEST_ORDER_SIZE,
        )

        exchange.step()

        assert buy_order.order_status == OrderStatus.open

        sell_order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=OrderSide.sell,
            size=tc.TEST_ORDER_SIZE,
        )

        exchange.step()

        assert buy_order.order_status == OrderStatus.open
        assert sell_order.order_status == OrderStatus.done
        assert sell_order.done_reason == DoneReason.cancelled

    @staticmethod
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell])
    def test_slippage_protection_limit_order(
        account: Account, exchange: Exchange, order_side: OrderSide
    ) -> None:
        """
        test_limit_order_slippage_protection [summary]

        Args:
            account (Account): [description]
            exchange (Exchange): [description]
            order_side (OrderSide): [description]
        """
        insert_orders = [
            CoinbaseOrder(
                order_id=generate_order_id(),
                order_status=OrderStatus.received,
                order_type=tc.TEST_ORDER_TYPE,
                product_id=tc.PRODUCT_ID,
                price=tc.TEST_PRICE_TYPE(str(price)),
                side=order_side.get_opposite_side(),
                size=tc.TEST_PRODUCT_TYPE.get_min_value(),
                time=exchange.interval_start_dt,
            )
            for price in np.arange(100, 200, 1.0)
        ]

        exchange.step(insert_orders=insert_orders)

        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.PRODUCT_ID.price_type("300.00")
            if order_side == OrderSide.buy
            else tc.PRODUCT_ID.price_type("50.00"),
            side=order_side,
            size=tc.TEST_ORDER_SIZE,
        )

        exchange.step()

        first_match_price = order.matches[0].price
        last_match_price = order.matches[-1].price
        num_price_slippage_points = (
            abs(first_match_price.amount - last_match_price.amount)
            / first_match_price.amount
        )

        if order_side == OrderSide.buy:
            assert len(order.matches) == 11
        else:
            assert len(order.matches) == 20
        assert order.order_status == OrderStatus.done
        assert order.done_reason == DoneReason.cancelled
        assert num_price_slippage_points <= SlippageProtection.MAX_PRICE_SLIPPAGE_POINTS

    @staticmethod
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell])
    def test_slippage_protection_market_order(
        exchange: Exchange, order_side: OrderSide
    ) -> None:
        """
        test_limit_order_slippage_protection [summary]

        Args:
            account (Account): [description]
            exchange (Exchange): [description]
            order_side (OrderSide): [description]
        """
        insert_orders = [
            CoinbaseOrder(
                order_id=generate_order_id(),
                order_status=OrderStatus.received,
                order_type=tc.TEST_ORDER_TYPE,
                product_id=tc.PRODUCT_ID,
                price=tc.PRODUCT_ID.price_type(str(price)),
                side=order_side.get_opposite_side(),
                size=tc.PRODUCT_ID.product_volume_type.get_min_value(),
                time=exchange.interval_start_dt,
            )
            for price in np.arange(100, 200, 1.0)
        ]

        exchange.step(insert_orders=insert_orders)

        order = CoinbaseOrder(
            funds=tc.TEST_WALLET_QUOTE_FUNDS,
            order_id=generate_order_id(),
            order_status=OrderStatus.received,
            order_type=OrderType.market,
            product_id=tc.PRODUCT_ID,
            side=order_side,
            time=exchange.interval_start_dt,
        )

        exchange.step(insert_orders=[order])

        first_match_price = order.matches[0].price
        last_match_price = order.matches[-1].price
        num_price_slippage_points = (
            abs(first_match_price.amount - last_match_price.amount)
            / first_match_price.amount
        )

        if order_side == OrderSide.buy:
            assert len(order.matches) == 11
        else:
            assert len(order.matches) == 20
        assert order.order_status == OrderStatus.done  # pylint: disable=W0143
        assert order.done_reason == DoneReason.cancelled
        assert num_price_slippage_points <= SlippageProtection.MAX_PRICE_SLIPPAGE_POINTS
