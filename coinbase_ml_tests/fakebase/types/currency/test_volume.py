"""
 [summary]
"""
import pytest

from coinbase_ml.fakebase.types import InvalidTypeError
from .... import constants as tc


class TestVolume:
    """
     [summary]
    """

    @staticmethod
    def test_volume_add() -> None:
        """
        test_volume_add [summary]
        """
        expected_volume = tc.QUOTE_CURRENCY.volume_type(
            tc.TEST_WALLET_QUOTE_FUNDS.amount + tc.TEST_WALLET_QUOTE_FUNDS.amount
        )
        actual_volume = tc.TEST_WALLET_QUOTE_FUNDS + tc.TEST_WALLET_QUOTE_FUNDS

        assert expected_volume == actual_volume
        assert isinstance(actual_volume, tc.QUOTE_CURRENCY.volume_type)

    @staticmethod
    def test_volume_add_exception() -> None:
        """
        test_volume_add_exception [summary]
        """
        with pytest.raises(InvalidTypeError):
            tc.TEST_WALLET_PRODUCT_FUNDS + tc.TEST_WALLET_QUOTE_FUNDS  # type: ignore # pylint: disable=pointless-statement

    @staticmethod
    def test_volume_comparison() -> None:
        """
        test_volume_max [summary]
        """
        min_volume = tc.TEST_WALLET_PRODUCT_FUNDS
        max_volume = tc.TEST_WALLET_PRODUCT_FUNDS + tc.TEST_WALLET_PRODUCT_FUNDS

        assert max(min_volume, max_volume) == max_volume
        assert min(min_volume, max_volume) == min_volume

    @staticmethod
    def test_volume_multiply() -> None:
        """
        test_volume_multiply [summary]
        """
        expected_volume = tc.QUOTE_CURRENCY.volume_type(
            tc.TEST_ORDER_PRICE.amount * tc.TEST_ORDER_SIZE.amount
        )

        actual_volume = tc.TEST_ORDER_PRICE * tc.TEST_ORDER_SIZE

        assert expected_volume == actual_volume
        assert isinstance(actual_volume, tc.QUOTE_CURRENCY.volume_type)

    @staticmethod
    def test_volume_multiply_exception() -> None:
        """
        test_volume_multiply_exception [summary]
        """
        with pytest.raises(InvalidTypeError):
            tc.TEST_WALLET_PRODUCT_FUNDS * tc.TEST_WALLET_PRODUCT_FUNDS  # type: ignore #pylint: disable=pointless-statement

    @staticmethod
    def test_volume_subtract() -> None:
        """
        test_volume_subtract [summary]
        """
        actual_volume = tc.TEST_WALLET_QUOTE_FUNDS - tc.TEST_WALLET_QUOTE_FUNDS

        assert tc.QUOTE_CURRENCY.zero_volume == actual_volume
        assert isinstance(actual_volume, tc.QUOTE_CURRENCY.volume_type)

    @staticmethod
    def test_zero_volume() -> None:
        """
        test_zero_volume
        """
        assert tc.QUOTE_CURRENCY.zero_volume.amount == 0
        assert isinstance(tc.QUOTE_CURRENCY.zero_volume, tc.QUOTE_CURRENCY.volume_type)
