"""
account_featurizer
"""
import numpy as np

from coinbase_ml.common import constants as c
from coinbase_ml.common.featurizers.types import Account


class AccountFeaturizer:
    """
    AccountFeaturizer
    """

    def __init__(self, account: Account) -> None:
        """
        __init__ [summary]

        Args:
            account (Account): [description]
        """
        self._account = account

    def get_funds_as_array(self) -> np.ndarray:
        """Summary

        Returns:
            np.ndarray: [usd_balance, usd_holds, btc_balance, btc_holds]
        """
        funds = self._account.funds
        _funds_as_array = np.hstack(
            [float(funds[currency].balance), float(funds[currency].holds)]
            for currency in self._account.currencies
        )

        return np.expand_dims(_funds_as_array, axis=0)

    def get_funds_as_normalized_array(self) -> np.ndarray:
        """
        get_funds_as_normalized_array [summary]

        Returns:
            np.ndarray: [description]
        """
        account_funds = self.get_funds_as_array()
        normalizer_array = np.array(
            (
                c.FUNDS_NORMALIZERS[c.QUOTE_CURRENCY],
                c.FUNDS_NORMALIZERS[c.QUOTE_CURRENCY],
                c.FUNDS_NORMALIZERS[c.PRODUCT_CURRENCY],
                c.FUNDS_NORMALIZERS[c.PRODUCT_CURRENCY],
            )
        )

        return account_funds / normalizer_array