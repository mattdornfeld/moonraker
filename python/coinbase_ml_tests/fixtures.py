"""
Common fixtures for unit tests
"""
from typing import Tuple

from pytest_cases import fixture_plus

from coinbase_ml.common.types import SimulationId
from coinbase_ml.fakebase.account import Account
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml_tests import constants as tc


@fixture_plus(unpack_into="account,exchange")
def create_exchange() -> Tuple[Account, Exchange, SimulationId]:
    """
    test_exchange [summary]

    Returns:
        Tuple[Account, Exchange]: [description]
    """
    simulation_id = tc.EXCHANGE.start(
        actionizer=tc.ACTIONIZER,
        end_dt=tc.EXCHANGE_END_DT,
        featurizer=tc.FEATURIZER,
        featurizer_configs=tc.FEATURIZER_CONFIGS,
        initial_product_funds=tc.TEST_WALLET_PRODUCT_FUNDS,
        initial_quote_funds=tc.TEST_WALLET_QUOTE_FUNDS,
        num_warmup_time_steps=0,
        product_id=tc.PRODUCT_ID,
        reward_strategy=tc.REWARD_STRATEGY,
        start_dt=tc.EXCHANGE_START_DT,
        time_delta=tc.EXCHANGE_TIME_DELTA,
    ).simulation_id

    return tc.EXCHANGE.account(simulation_id), tc.EXCHANGE, simulation_id
