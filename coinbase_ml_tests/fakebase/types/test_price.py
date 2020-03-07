"""
 [summary]
"""
from ... import constants as tc


class TestPrice:
    """
     [summary]
    """

    @staticmethod
    def test_price_comparison() -> None:
        """
        test_price_comparison [summary]
        """
        assert (
            max(tc.PRODUCT_ID.min_price, tc.PRODUCT_ID.max_price)
            == tc.PRODUCT_ID.max_price
        )
        assert (
            min(tc.PRODUCT_ID.min_price, tc.PRODUCT_ID.max_price)
            == tc.PRODUCT_ID.min_price
        )
