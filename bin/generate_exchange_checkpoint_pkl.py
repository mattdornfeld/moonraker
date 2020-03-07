"""
Generates fakebase_tests/data/test_exchange_checkpoint.p
"""
import pickle

from coinbase_ml_tests import constants as tc
from coinbase_ml_tests.conftest import create_database_in_container, wait_for_database
from coinbase_ml_tests.fakebase.test_exchange_integration import (
    create_exchange_with_db_connection,
)

if __name__ == "__main__":
    CONTAINER = create_database_in_container(tc.TEST_SQL_SRC_DIR)
    wait_for_database()
    try:
        EXCHANGE = create_exchange_with_db_connection()

        for _ in range(tc.TEST_EXCHANGE_NUM_STEPS):
            EXCHANGE.step()

        with open(tc.TEST_EXCHANGE_PKL_PATH, "wb") as f:
            pickle.dump(EXCHANGE.create_checkpoint(), f)
    finally:
        CONTAINER.stop()
