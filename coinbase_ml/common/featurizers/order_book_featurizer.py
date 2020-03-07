"""
order_book_featurizer
"""
from typing import DefaultDict, Generic, Tuple

import numpy as np

from coinbase_ml.common import constants as c
from coinbase_ml.common.featurizers.types import Exchange
from coinbase_ml.common.utils.preprocessing_utils import min_max_normalization
from coinbase_ml.fakebase.types import OrderSide, ProductPrice, ProductVolume


class OrderBookFeaturizer(Generic[Exchange]):
    """
    OrderBookFeaturizer
    """

    def __init__(self, exchange: Exchange) -> None:
        """
        __init__ [summary]

        Args:
            exchange (Exchange): [description]
        """
        self.exchange = exchange

    @staticmethod
    def _normalize_price_volume(
        price: ProductPrice, volume: ProductVolume
    ) -> Tuple[float, float]:
        """
        _normalize_price_volume [summary]

        Args:
            price (ProductPrice): [description]
            volume (ProductVolume): [description]

        Returns:
            Tuple[float, float]: [description]
        """
        return (
            min_max_normalization(c.PRICE_NORMALIZER, 0, float(price.amount)),
            min_max_normalization(c.SIZE_NORMALIZER, 0, float(volume.amount)),
        )

    @staticmethod
    def _price_volume_dict_to_array(
        price_volume_dict: DefaultDict[ProductPrice, ProductVolume]
    ) -> np.ndarray:
        """
        _price_volume_dict_to_array [summary]

        Args:
            price_volume_dict (DefaultDict[ProductPrice, ProductVolume]): [description]

        Returns:
            np.ndarray: [description]
        """
        price_volume_list = [
            OrderBookFeaturizer._normalize_price_volume(price, volume)
            for price, volume in price_volume_dict.items()
        ]

        def sort_key(price_volume: Tuple[float, float]) -> float:
            return price_volume[0]

        price_volume_list.sort(key=sort_key)

        return np.array(price_volume_list) if price_volume_dict else np.zeros((1, 2))

    def get_order_book_features(self, order_side: OrderSide) -> np.ndarray:
        """
        get_order_book_features [summary]

        Args:
            order_side (OrderSide): [description]

        Returns:
            np.ndarray: [description]
        """
        price_volume_dict = self.exchange.bin_order_book_by_price(order_side)

        return self._price_volume_dict_to_array(price_volume_dict)
