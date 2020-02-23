"""
 [summary]
"""
from fakebase.orm import CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder
from fakebase_tests import constants as tc


def test_cancellation_init() -> None:
    """
    test_cancellation_init [summary]
    """
    cancellation = CoinbaseCancellation(
        price=tc.TEST_ORDER_PRICE,
        product_id=tc.PRODUCT_ID,
        order_id=tc.TEST_ORDER_ID,
        side=tc.TEST_ORDER_SIDE,
        remaining_size=tc.TEST_ORDER_SIZE,
        time=tc.TEST_ORDER_TIME,
    )

    assert cancellation.price == tc.TEST_ORDER_PRICE
    assert cancellation.product_id == tc.PRODUCT_ID  # pylint: disable=W0143
    assert cancellation.order_id == tc.TEST_ORDER_ID
    assert cancellation.side == tc.TEST_ORDER_SIDE
    assert cancellation.remaining_size == tc.TEST_ORDER_SIZE
    assert cancellation.time == tc.TEST_ORDER_TIME


def test_match_init() -> None:
    """
    test_match_init [summary]
    """
    match = CoinbaseMatch(
        maker_order_id=tc.TEST_ORDER_ID,
        price=tc.TEST_ORDER_PRICE,
        product_id=tc.PRODUCT_ID,
        sequence=tc.TEST_ORDER_SEQUENCE,
        side=tc.TEST_ORDER_SIDE,
        size=tc.TEST_ORDER_SIZE,
        taker_order_id=tc.TEST_ORDER_ID,
        time=tc.TEST_ORDER_TIME,
        trade_id=tc.TEST_TRADE_ID,
    )

    assert match.maker_order_id == tc.TEST_ORDER_ID
    assert match.price == tc.TEST_ORDER_PRICE
    assert match.product_id == tc.PRODUCT_ID  # pylint: disable=W0143
    assert match.sequence == tc.TEST_ORDER_SEQUENCE
    assert match.side == tc.TEST_ORDER_SIDE
    assert match.size == tc.TEST_ORDER_SIZE
    assert match.taker_order_id == tc.TEST_ORDER_ID
    assert match.time == tc.TEST_ORDER_TIME
    assert match.trade_id == tc.TEST_TRADE_ID


def test_order_init() -> None:
    """
    test_order_init [summary]
    """
    order = CoinbaseOrder(
        client_oid=tc.TEST_ORDER_CLIENT_OID,
        funds=tc.TEST_ORDER_FUNDS,
        order_id=tc.TEST_ORDER_ID,
        order_status=tc.TEST_ORDER_STATUS,
        order_type=tc.TEST_ORDER_TYPE,
        post_only=tc.TEST_ORDER_POST_ONLY,
        product_id=tc.PRODUCT_ID,
        price=tc.TEST_ORDER_PRICE,
        sequence=tc.TEST_ORDER_SEQUENCE,
        side=tc.TEST_ORDER_SIDE,
        size=tc.TEST_ORDER_SIZE,
        time=tc.TEST_ORDER_TIME,
        time_in_force=tc.TEST_ORDER_TIME_IN_FORCE,
        time_to_live=tc.TEST_ORDER_TIME_TO_LIVE,
    )

    assert order.client_oid == tc.TEST_ORDER_CLIENT_OID
    assert order.funds == tc.TEST_ORDER_FUNDS
    assert order.order_id == tc.TEST_ORDER_ID
    assert order.order_status == tc.TEST_ORDER_STATUS  # pylint: disable=W0143
    assert order.order_type == tc.TEST_ORDER_TYPE  # pylint: disable=W0143
    assert order.post_only == tc.TEST_ORDER_POST_ONLY
    assert order.product_id == tc.PRODUCT_ID  # pylint: disable=W0143
    assert order.price == tc.TEST_ORDER_PRICE
    assert order.sequence == tc.TEST_ORDER_SEQUENCE
    assert order.side == tc.TEST_ORDER_SIDE
    assert order.size == tc.TEST_ORDER_SIZE
    assert order.time == tc.TEST_ORDER_TIME
    assert order.time_in_force == tc.TEST_ORDER_TIME_IN_FORCE
    assert order.time_to_live == tc.TEST_ORDER_TIME_TO_LIVE
