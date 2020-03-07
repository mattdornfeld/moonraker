"""
account_featurizer
"""
from typing import Generic

import numpy as np

from .types import Account
from .. import constants as c


class AccountFeaturizer(Generic[Account]):
    """
    AccountFeaturizer
    """

    NORMALIZER_ARRAY = np.array(
        (
            c.FUNDS_NORMALIZERS[c.QUOTE_CURRENCY],
            c.FUNDS_NORMALIZERS[c.QUOTE_CURRENCY],
            c.FUNDS_NORMALIZERS[c.PRODUCT_CURRENCY],
            c.FUNDS_NORMALIZERS[c.PRODUCT_CURRENCY],
        )
    )

    def __init__(self, account: Account) -> None:
        """
        __init__ [summary]

        Args:
            account (Account): [description]
        """
        self.account = account

    def get_funds_as_array(self) -> np.ndarray:
        """Summary

        Returns:
            np.ndarray: [usd_balance, usd_holds, btc_balance, btc_holds]
        """
        funds = self.account.funds
        _funds_as_array = np.hstack(
            [
                [
                    float(funds[currency].balance.amount),
                    float(funds[currency].holds.amount),
                ]
                for currency in self.account.currencies
            ]
        )

        return np.expand_dims(_funds_as_array, axis=0)

    def get_funds_as_normalized_array(self) -> np.ndarray:
        """
        get_funds_as_normalized_array [summary]

        Returns:
            np.ndarray: [description]
        """
        return self.get_funds_as_array() / AccountFeaturizer.NORMALIZER_ARRAY
