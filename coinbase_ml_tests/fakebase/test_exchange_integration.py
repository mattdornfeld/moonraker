"""
Integration tests for exchange.py
"""
import pickle
from typing import List

import pytest

from fakebase import constants as c
from fakebase.account import Account
from fakebase.exchange import Exchange, ExchangeCheckpoint
from fakebase.orm import CoinbaseOrder
from fakebase.types import Liquidity, OrderSide, OrderStatus, RejectReason
from fakebase.utils import set_seed
from fakebase.utils.exceptions import ExchangeFinishedException
from fakebase_tests import constants as tc


def create_exchange_with_db_connection() -> Exchange:
    """
    create_exchange_with_db_connection [summary]

    Returns:
        Exchange: [description]
    """
    c.NUM_DATABASE_WORKERS = 1

    db_exchange = Exchange(
        end_dt=tc.EXCHANGE_END_DT,
        product_id=tc.PRODUCT_ID,
        start_dt=tc.EXCHANGE_START_DT,
        time_delta=tc.EXCHANGE_TIME_DELTA,
    )

    set_seed(1)
    account = Account(db_exchange)
    account.add_funds(currency=tc.QUOTE_CURRENCY, amount=tc.TEST_WALLET_QUOTE_FUNDS)
    account.add_funds(currency=tc.PRODUCT_CURRENCY, amount=tc.TEST_WALLET_PRODUCT_FUNDS)
    db_exchange.account = account

    # Do an exchange restore to ensure that doing so
    # doesn't cause tests to fail
    db_exchange = db_exchange.create_checkpoint().restore()
    set_seed(1)

    return db_exchange


class TestExchangeIntegration:
    """
    Integration tests for exchange.py
    """

    @staticmethod
    @pytest.mark.integration_tests
    def test_bin_order_book_by_price(
        create_database_stop_when_finished: None,  # pylint: disable=unused-argument
    ) -> None:
        """
        test_bin_order_book_by_price [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        exchange = create_exchange_with_db_connection()

        exchange.step()

        buy_order_book = exchange.bin_order_book_by_price(OrderSide.buy)
        sell_order_book = exchange.bin_order_book_by_price(OrderSide.sell)

        assert len(buy_order_book) == 46
        assert buy_order_book.pop(
            tc.TEST_PRICE_TYPE("9501.46")
        ) == tc.TEST_PRODUCT_TYPE("0.01000000")
        assert len(sell_order_book) == 38
        assert sell_order_book.pop(
            tc.TEST_PRICE_TYPE("9659.05")
        ) == tc.TEST_PRODUCT_TYPE("0.00100000")

    @staticmethod
    @pytest.mark.integration_tests
    def test_exchange_end_state(
        create_database_stop_when_finished: None,  # pylint: disable=unused-argument
    ) -> None:
        """
        test_exchange_integration [summary]

        Returns:
            None: [description]
        """
        with open(tc.TEST_EXCHANGE_PKL_PATH, "rb") as f:
            test_exchange_checkpoint: ExchangeCheckpoint = pickle.load(f)

        test_exchange = test_exchange_checkpoint.restore()

        exchange = create_exchange_with_db_connection()

        for _ in range(tc.TEST_EXCHANGE_NUM_STEPS):
            exchange.step()

        assert test_exchange == exchange

    @staticmethod
    @pytest.mark.integration_tests
    def test_exchange_finished_exception(
        create_database_stop_when_finished: None,  # pylint: disable=unused-argument
    ) -> None:
        """
        test_exchange_finished_exception [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        exchange = create_exchange_with_db_connection()

        for _ in range(tc.TEST_EXCHANGE_NUM_STEPS):
            exchange.step()

        with pytest.raises(ExchangeFinishedException):
            exchange.step()

    @staticmethod
    @pytest.mark.integration_tests
    def test_exchange_order_stream(
        create_database_stop_when_finished: None,  # pylint: disable=unused-argument
    ) -> None:
        """
        test_exchange_order_stream [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        exchange = create_exchange_with_db_connection()

        exchange.step()

        assert len(exchange.received_orders) == 872
        assert len(exchange.received_cancellations) == 862
        assert len(exchange.matches) == 3

        exchange.step()

        assert len(exchange.received_orders) == 425
        assert len(exchange.received_cancellations) == 416
        assert len(exchange.matches) == 3

    @staticmethod
    @pytest.mark.integration_tests
    def test_fee_fraction_maker(
        create_database_stop_when_finished: None,  # pylint: disable=unused-argument
    ) -> None:
        """
        test_fee_fraction [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        exchange = create_exchange_with_db_connection()
        account = exchange.account

        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=tc.TEST_ORDER_SIDE,
            size=tc.TEST_ORDER_SIZE,
        )

        exchange.step()

        assert order.is_taker is False
        assert [match.liquidity for match in order.matches] == [
            Liquidity.maker for _ in range(len(order.matches))
        ]
        assert order.fill_fees == order.executed_value * Liquidity.maker.fee_fraction

    @staticmethod
    @pytest.mark.integration_tests
    def test_fee_fraction_taker(
        create_database_stop_when_finished: None,  # pylint: disable=unused-argument
    ) -> None:
        """
        test_fee_fraction [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        exchange = create_exchange_with_db_connection()
        account = exchange.account
        exchange.step()

        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=tc.TEST_ORDER_SIDE,
            size=tc.TEST_ORDER_SIZE,
        )

        exchange.step()

        assert order.is_taker is True
        assert [match.liquidity for match in order.matches] == [
            Liquidity.taker for _ in range(len(order.matches))
        ]
        assert order.fill_fees == order.executed_value * Liquidity.taker.fee_fraction

    @staticmethod
    @pytest.mark.integration_tests
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell])
    def test_match_limit_order(
        create_database_stop_when_finished: None,  # pylint: disable=unused-argument
        order_side: OrderSide,
    ) -> None:
        """
        test_match_making [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        exchange = create_exchange_with_db_connection()
        account = exchange.account
        price = (
            tc.TEST_ORDER_PRICE
            if order_side == OrderSide.buy
            else tc.TEST_PRICE_TYPE("1.00")
        )

        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=price,
            side=order_side,
            size=tc.TEST_ORDER_SIZE,
        )

        exchange.step()

        if order_side == OrderSide.buy:
            expected_quote_balance = (
                tc.TEST_WALLET_QUOTE_FUNDS
                - order.price * order.size * (1 + Liquidity.maker.fee_fraction)
            )

            expected_product_balance = tc.TEST_WALLET_PRODUCT_FUNDS + sum(
                [m.size for m in order.matches], tc.ZERO_PRODUCT
            )

        else:
            volume = sum([m.usd_volume for m in order.matches], tc.ZERO_QUOTE)
            fees = sum([m.fee for m in order.matches], tc.ZERO_QUOTE)
            expected_quote_balance = tc.TEST_WALLET_QUOTE_FUNDS + volume - fees
            expected_product_balance = tc.TEST_WALLET_PRODUCT_FUNDS - order.size

        assert expected_quote_balance == account.funds[tc.QUOTE_CURRENCY].balance
        assert expected_product_balance == account.funds[tc.PRODUCT_CURRENCY].balance
        assert account.funds[tc.QUOTE_CURRENCY].holds == tc.ZERO_QUOTE
        assert account.funds[tc.PRODUCT_CURRENCY].holds == tc.ZERO_PRODUCT
        assert order.order_status == OrderStatus.done

    @staticmethod
    @pytest.mark.integration_tests
    @pytest.mark.parametrize("order_side", [OrderSide.buy, OrderSide.sell])
    def test_match_market_order(
        create_database_stop_when_finished: None,  # pylint: disable=unused-argument
        order_side: OrderSide,
    ) -> None:
        """
        test_match_market_order [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        exchange = create_exchange_with_db_connection()
        account = exchange.account
        exchange.step()

        if order_side == OrderSide.buy:
            order = account.place_market_order(
                funds=tc.TEST_ORDER_FUNDS, product_id=tc.PRODUCT_ID, side=order_side
            )

        else:
            order = account.place_market_order(
                product_id=tc.PRODUCT_ID, side=order_side, size=tc.TEST_ORDER_SIZE
            )

        exchange.step()

        if order_side == OrderSide.buy:
            expected_quote_balance = (
                tc.TEST_WALLET_QUOTE_FUNDS
                - order.original_funds * (1 + Liquidity.taker.fee_fraction)
            )

            expected_product_balance = tc.TEST_WALLET_PRODUCT_FUNDS + sum(
                [m.size for m in order.matches], tc.ZERO_PRODUCT
            )

        else:
            volume = sum([m.usd_volume for m in order.matches], tc.ZERO_QUOTE)
            fees = sum([m.fee for m in order.matches], tc.ZERO_QUOTE)
            expected_quote_balance = tc.TEST_WALLET_QUOTE_FUNDS + volume - fees
            expected_product_balance = tc.TEST_WALLET_PRODUCT_FUNDS - order.size

        assert expected_quote_balance == account.funds[tc.QUOTE_CURRENCY].balance
        assert expected_product_balance == account.funds[tc.PRODUCT_CURRENCY].balance
        assert account.funds[tc.QUOTE_CURRENCY].holds.is_zero()
        assert account.funds[tc.PRODUCT_CURRENCY].holds.is_zero()
        assert order.order_status == OrderStatus.done

    @staticmethod
    @pytest.mark.integration_tests
    def test_order_time_to_live(
        create_database_stop_when_finished: None,  # pylint: disable=unused-argument
    ) -> None:
        """
        test_order_time_to_live [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        exchange = create_exchange_with_db_connection()
        account = exchange.account

        def _step_and_cancel_expired_orders() -> List[CoinbaseOrder]:
            exchange.step()
            return account.cancel_expired_orders()

        order = account.place_limit_order(
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_PRICE_TYPE("1e-2"),
            side=tc.TEST_ORDER_SIDE,
            size=tc.TEST_ORDER_SIZE,
            time_to_live=tc.TEST_ORDER_TIME_TO_LIVE,
        )

        _step_and_cancel_expired_orders()

        assert order.order_status == OrderStatus.open

        _step_and_cancel_expired_orders()
        cancelled_orders = _step_and_cancel_expired_orders()

        assert cancelled_orders == [order]
        assert order.order_status == OrderStatus.done

    @staticmethod
    @pytest.mark.integration_tests
    def test_reject_post_only(
        create_database_stop_when_finished: None,  # pylint: disable=unused-argument
    ) -> None:
        """
        test_reject_post_only [summary]

        Args:
            create_database_stop_when_finished (None): [description]
        """
        exchange = create_exchange_with_db_connection()
        account = exchange.account
        exchange.step()

        order = account.place_limit_order(
            post_only=True,
            product_id=tc.PRODUCT_ID,
            price=tc.TEST_ORDER_PRICE,
            side=tc.TEST_ORDER_SIDE,
            size=tc.TEST_ORDER_SIZE,
        )

        assert order.reject_reason == RejectReason.post_only
