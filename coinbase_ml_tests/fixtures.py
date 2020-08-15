"""
Common fixtures for unit tests
"""
from typing import Tuple

from pytest_cases import fixture_plus

from coinbase_ml.fakebase import constants as fc
from coinbase_ml.fakebase.account import Account
from coinbase_ml.fakebase.exchange import Exchange

from coinbase_ml_tests import constants as tc


@fixture_plus(unpack_into="account,exchange")
def create_exchange() -> Tuple[Account, Exchange]:
    """
    test_exchange [summary]

    Returns:
        Tuple[Account, Exchange]: [description]
    """
    fc.NUM_DATABASE_WORKERS = 0

    tc.EXCHANGE.start(
        initial_product_funds=tc.TEST_WALLET_PRODUCT_FUNDS,
        initial_quote_funds=tc.TEST_WALLET_QUOTE_FUNDS,
        num_warmup_time_steps=0,
        snapshot_buffer_size=3,
    )

    return tc.EXCHANGE.account, tc.EXCHANGE
