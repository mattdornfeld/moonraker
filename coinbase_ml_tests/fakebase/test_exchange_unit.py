from pytest_cases import unpack_fixture

from coinbase_ml.fakebase.account import Account
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.common.types import SimulationId
from coinbase_ml.fakebase.types import DoneReason, OrderStatus

from coinbase_ml_tests import constants as tc
from coinbase_ml_tests.fixtures import create_exchange

(  # pylint: disable=unbalanced-tuple-unpacking
    account,  # pylint: disable=invalid-name
    exchange,  # pylint: disable=invalid-name
    simulation_id,
) = unpack_fixture("account,exchange,simulation_id", create_exchange)

# pylint: disable=redefined-outer-name


class TestExchangeUnit:
    """
    unit tests for exchange.py
    """

    @staticmethod
    def test_cancel_order(
        account: Account, exchange: Exchange, simulation_id: SimulationId
    ) -> None:
        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=tc.TEST_ORDER_SIDE,
            size=tc.TEST_ORDER_SIZE,
        )

        exchange.step(simulation_id)

        assert order.order_id in account.orders
        assert account.orders[order.order_id].order_status == OrderStatus.open

        account.cancel_order(order.order_id)
        exchange.step(simulation_id)

        assert account.orders[order.order_id].order_status == OrderStatus.done
        assert account.orders[order.order_id].done_reason == DoneReason.canceled

    @staticmethod
    def test_finished_false(exchange: Exchange, simulation_id: SimulationId) -> None:
        assert exchange.finished(simulation_id) is False

    @staticmethod
    def test_finished_true(exchange: Exchange, simulation_id: SimulationId) -> None:
        for _ in range(4):
            exchange.step(simulation_id)

        assert exchange.finished(simulation_id) is True
