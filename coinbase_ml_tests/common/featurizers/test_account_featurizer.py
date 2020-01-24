"""
tests for AccountFeaturizer
"""
from typing import Tuple

import numpy as np

from fakebase.account import Account
from fakebase.exchange import Exchange
from fakebase_tests import constants as ftc
from fakebase_tests.test_exchange_unit import create_exchange  # pylint: disable=W0611

import pytest

from coinbase_ml.common.featurizers import AccountFeaturizer


@pytest.fixture(name="account_featurizer")
def create_account_featurizer(
    create_exchange: Tuple[Account, Exchange]  # pylint: disable=W0621
) -> AccountFeaturizer:
    """
    create_account_featurizer [summary]

    Args:
        create_exchange (Tuple[Account, Exchange]): [description]

    Returns:
        AccountFeaturizer: [description]
    """
    account, _ = create_exchange

    return AccountFeaturizer[Account](account)


@pytest.fixture
def expected_account_funds() -> np.ndarray:
    """
    expected_account_funds [summary]

    Returns:
        np.ndarray: [description]
    """
    return (
        np.array([ftc.TEST_WALLET_QUOTE_FUNDS, 0.0, ftc.TEST_WALLET_PRODUCT_FUNDS, 0.0])
        .astype(float)
        .reshape(1, 4)
    )


class TestAccountFeaturizer:
    """
    TestAccountFeaturizer
    """

    @staticmethod
    def test_get_funds_as_array(
        account_featurizer: AccountFeaturizer,
        expected_account_funds: np.ndarray,  # pylint: disable=W0621
    ) -> None:
        """
        test_get_funds_as_array [summary]

        Args:
            account_featurizer (AccountFeaturizer): [description]
            expected_account_funds (np.ndarray): [description]
        """
        np.testing.assert_array_almost_equal(
            account_featurizer.get_funds_as_array(), expected_account_funds, 10
        )

    @staticmethod
    def test_get_funds_as_normalized_array(
        account_featurizer: AccountFeaturizer,
        expected_account_funds: np.ndarray,  # pylint: disable=W0621
    ) -> None:
        """
        test_get_funds_as_array [summary]

        Args:
            account_featurizer (AccountFeaturizer): [description]
            expected_account_funds (np.ndarray): [description]
        """
        np.testing.assert_array_almost_equal(
            account_featurizer.get_funds_as_normalized_array(),
            expected_account_funds / AccountFeaturizer.NORMALIZER_ARRAY,
            10,
        )
