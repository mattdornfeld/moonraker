"""
Common fixtures for unit and integration tests
"""
from typing import Tuple

from pytest_cases import fixture_plus

from coinbase_ml.fakebase import constants as fc
from coinbase_ml.fakebase.account import Account
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.fakebase.utils import set_seed

from . import constants as tc


@fixture_plus(unpack_into="account,exchange")
def create_exchange() -> Tuple[Account, Exchange]:
    """
    test_exchange [summary]

    Returns:
        Tuple[Account, Exchange]: [description]
    """
    fc.NUM_DATABASE_WORKERS = 0
    # Set seed here to control randomness when account ids are created
    set_seed(1)
    exchange = Exchange(
        end_dt=tc.EXCHANGE_END_DT,
        product_id=tc.PRODUCT_ID,
        start_dt=tc.EXCHANGE_START_DT,
        time_delta=tc.EXCHANGE_TIME_DELTA,
    )
    exchange.account.add_funds(
        currency=tc.QUOTE_CURRENCY, amount=tc.TEST_WALLET_QUOTE_FUNDS
    )
    exchange.account.add_funds(
        currency=tc.PRODUCT_CURRENCY, amount=tc.TEST_WALLET_PRODUCT_FUNDS
    )

    # Do an exchange restore to ensure that doing so
    # doesn't cause tests to fail.
    # Set seed here to control randomness of order execution
    set_seed(1)
    exchange = exchange.create_checkpoint().restore()

    return exchange.account, exchange


def create_exchange_with_db_connection() -> Exchange:
    """
    create_exchange_with_db_connection [summary]

    Returns:
        Exchange: [description]
    """
    fc.NUM_DATABASE_WORKERS = 1

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
