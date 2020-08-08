"""
 [summary]
"""
from pytest_cases import unpack_fixture

from coinbase_ml.fakebase.account import Account
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.fakebase.types import DoneReason, OrderStatus

from coinbase_ml_tests import constants as tc
from coinbase_ml_tests.fixtures import create_exchange

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

        assert order.order_id in account.orders
        assert account.orders[order.order_id].order_status == OrderStatus.open

        account.cancel_order(order.order_id)
        exchange.step()

        assert account.orders[order.order_id].order_status == OrderStatus.done
        assert account.orders[order.order_id].done_reason == DoneReason.cancelled

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
        for _ in range(5):
            exchange.step()

        assert exchange.finished is True
