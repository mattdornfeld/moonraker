"""
 [summary]
"""
import pytest

from fakebase import constants as c
from fakebase.account import Account
from fakebase.exchange import Exchange
from fakebase.orm import CoinbaseMatch
from fakebase.utils import set_seed
from fakebase.types import DoneReason, Liquidity, OrderSide, OrderStatus
from fakebase_tests import constants as tc


@pytest.fixture(name="account")
def create_account() -> Account:
    """
    create_account [summary]

    Returns:
        Account: [description]
    """
    c.NUM_DATABASE_WORKERS = 0
    # Set seed here to control randomness when account ids are created
    set_seed(1)
    exchange = Exchange(
        end_dt=tc.EXCHANGE_END_DT,
        product_id=tc.PRODUCT_ID,
        start_dt=tc.EXCHANGE_START_DT,
        time_delta=tc.EXCHANGE_TIME_DELTA,
    )
    account = exchange.account

    account.add_funds(currency=tc.QUOTE_CURRENCY, amount=tc.TEST_WALLET_QUOTE_FUNDS)
    account.add_funds(currency=tc.PRODUCT_CURRENCY, amount=tc.TEST_WALLET_PRODUCT_FUNDS)

    return account


class TestAccountUnit:
    """
     [summary]
    """

    @staticmethod
    def test_add_funds(account: Account) -> None:
        """
        test_add_funds [summary]

        Args:
            account (Account): [description]
        """
        assert account.funds[tc.QUOTE_CURRENCY].balance == tc.TEST_WALLET_QUOTE_FUNDS
        assert (
            account.funds[tc.PRODUCT_CURRENCY].balance == tc.TEST_WALLET_PRODUCT_FUNDS
        )

    @staticmethod
    def test_add_match(account: Account) -> None:
        """
        test_add_match [summary]

        Args:
            account (Account): [description]
        """
        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=tc.TEST_ORDER_SIDE,
            size=tc.TEST_ORDER_SIZE,
        )

        match = CoinbaseMatch(
            price=tc.TEST_ORDER_PRICE,
            product_id=tc.PRODUCT_ID,
            maker_order_id=order.order_id,
            taker_order_id=tc.TEST_ORDER_ID,
            trade_id=tc.TEST_TRADE_ID,
            side=tc.TEST_ORDER_SIDE.get_opposite_side(),
            size=tc.TEST_ORDER_SIZE,
            time=tc.TEST_ORDER_TIME,
        )

        account.process_match(match, order.order_id)
        assert (
            match.usd_volume + match.fee
            == tc.TEST_WALLET_QUOTE_FUNDS - account.funds[tc.QUOTE_CURRENCY].balance
        )
        assert (
            match.size
            == account.funds[tc.PRODUCT_CURRENCY].balance - tc.TEST_WALLET_PRODUCT_FUNDS
        )

    @staticmethod
    @pytest.mark.parametrize("order_status", [OrderStatus.received, OrderStatus.open])
    def test_cancel_order(account: Account, order_status: OrderStatus) -> None:
        """
        test_cancel_order [summary]

        Args:
            account (Account): [description]
            order_status (OrderStatus): [description]
        """
        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=tc.TEST_ORDER_SIDE,
            size=tc.TEST_ORDER_SIZE,
        )

        if order_status == OrderStatus.open:
            account.open_order(order.order_id)

        account.cancel_order(order.order_id)

        assert order.order_status == OrderStatus.done
        assert order.done_reason == DoneReason.cancelled
        assert account.funds[tc.QUOTE_CURRENCY].holds.is_zero()

    @staticmethod
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell])
    def test_close_order(account: Account, order_side: OrderSide) -> None:
        """
        test_close_order [summary]

        Args:
            account (Account): [description]
            order_side (OrderSide): [description]
        """
        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=order_side,
            size=tc.TEST_ORDER_SIZE,
        )

        account.close_order(order.order_id, tc.TEST_ORDER_TIME)

        assert order.order_status == OrderStatus.done
        assert order.done_reason == DoneReason.filled
        assert order.done_at == tc.TEST_ORDER_TIME
        if order_side == OrderSide.buy:
            assert account.funds[tc.QUOTE_CURRENCY].holds.is_zero()
        else:
            assert account.funds[tc.PRODUCT_CURRENCY].holds.is_zero()

    @staticmethod
    def test_copy(account: Account) -> None:
        """
        test_copy [summary]

        Args:
            account (Account): [description]
        """
        copied_account = account.copy()
        assert account == copied_account

    @staticmethod
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell])
    def test_open_order(account: Account, order_side: OrderSide) -> None:
        """
        test_open_order [summary]

        Args:
            account (Account): [description]
            order_side (OrderSide): [description]
        """
        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=order_side,
            size=tc.TEST_ORDER_SIZE,
        )

        account.open_order(order.order_id)

        assert order.order_status == OrderStatus.open
        if order_side == OrderSide.buy:
            assert account.funds[tc.QUOTE_CURRENCY].holds == order.price * order.size
        else:
            assert account.funds[tc.PRODUCT_CURRENCY].holds == order.size

    @staticmethod
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell])
    def test_place_limit_order(account: Account, order_side: OrderSide) -> None:
        """
        test_place_limit_order [summary]

        Args:
            account (Account): [description]
            order_side (OrderSide): [description]
        """
        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=order_side,
            size=tc.TEST_ORDER_SIZE,
        )

        assert account.orders[order.order_id] == order

        if order_side == OrderSide.buy:
            assert account.funds[tc.QUOTE_CURRENCY].holds == account.calc_buy_hold(
                order
            )
        else:
            assert account.funds[tc.PRODUCT_CURRENCY].holds == order.size

    @staticmethod
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell])
    def test_place_market_order(account: Account, order_side: OrderSide) -> None:
        """
        test_place_market_order [summary]

        Args:
            account (Account): [description]
            order_side (OrderSide): [description]
        """
        if order_side == OrderSide.buy:
            order = account.place_market_order(
                funds=tc.TEST_ORDER_FUNDS, product_id=tc.PRODUCT_ID, side=order_side
            )

            assert account.funds[tc.QUOTE_CURRENCY].holds == order.funds * (
                1 + Liquidity.taker.fee_fraction
            )
        else:
            order = account.place_market_order(
                product_id=tc.PRODUCT_ID, side=order_side, size=tc.TEST_ORDER_SIZE
            )

            assert account.funds[tc.PRODUCT_CURRENCY].holds == order.size

        assert account.orders[order.order_id] == order

        account.exchange.step()

        assert order.done_reason == DoneReason.cancelled

        if order_side == OrderSide.buy:
            assert account.funds[tc.QUOTE_CURRENCY].holds.is_zero()
        else:
            assert account.funds[tc.PRODUCT_CURRENCY].holds.is_zero()
