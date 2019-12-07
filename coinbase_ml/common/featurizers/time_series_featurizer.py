"""
time_series_featurizer
"""
from statistics import mean
from typing import Any, Callable, List, TypeVar

from funcy import compose, partial
import numpy as np

from fakebase.orm import CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder

from coinbase_ml.common.featurizers.types import Exchange
from coinbase_ml.common.utils import stdev
from coinbase_ml.common.utils.preprocessing_utils import NormalizedOperation

Event = TypeVar("Event", CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder)


class TimeSeriesFeaturizer:
    """
    TimeSeriesFeaturizer
    """

    def __init__(self, exchange: Exchange) -> None:
        """
        __init__ [summary]

        Args:
            exchange (Exchange): [description]
        """
        self._exchange = exchange
        self._cancellation_operators: List[NormalizedOperation] = []
        self._match_operators: List[NormalizedOperation] = []
        self._order_operators: List[NormalizedOperation] = []
        self._create_time_series_operators()

    @staticmethod
    def _calc_event_counts(order_side: str, events: List[Event]) -> int:
        """Summary

        Args:
            order_side (str): Description
            events (List[Event]): Description

        Returns:
            int: Description
        """
        return len([e for e in events if e.side == order_side])

    def _calc_op_for_attribute(
        self,
        attribute: str,
        operation: Callable[[List[Any]], float],
        order_side: str,
        events: List[Event],
    ) -> float:
        """Summary
        Args:
            attribute (str): Description
            operation (Callable[[List[Any]], float]): Description
            order_side (str): Description
            events (List[Event]): Description

        Returns:
            float: Description
        """
        filtered_events = self._filter_events(attribute, events, order_side)

        return operation(filtered_events) if filtered_events else 0.0

    def _create_time_series_operators(self) -> None:
        """
        _create_time_series_operators [summary]
        """
        for order_side in ["buy", "sell"]:

            operator = partial(self._calc_event_counts, order_side)

            name = f"{order_side}_count"
            self._cancellation_operators.append(NormalizedOperation(operator, name))
            self._match_operators.append(NormalizedOperation(operator, name))
            self._order_operators.append(NormalizedOperation(operator, name))

            for attribute in ["price", "size"]:
                for _operator in [mean, stdev]:
                    operator = partial(
                        self._calc_op_for_attribute,
                        attribute,
                        compose(float, _operator),
                        order_side,
                    )

                    name = f"{order_side}_{attribute}_{_operator.__name__}"

                    if attribute in ["price"]:
                        self._cancellation_operators.append(
                            NormalizedOperation(operator, name)
                        )

                    self._match_operators.append(NormalizedOperation(operator, name))
                    self._order_operators.append(NormalizedOperation(operator, name))

    @staticmethod
    def _filter_events(
        attribute: str, events: List[Event], order_side: str
    ) -> List[Any]:
        """Summary
        Args:
            attribute (str): Description
            events (List[Event]): Description
            order_side (str): Description
        Returns:
            List[Any]: Description
        """
        return [
            getattr(e, attribute)
            for e in events
            if e.side == order_side and getattr(e, attribute) is not None
        ]

    def get_time_series_features(self) -> np.ndarray:
        """
        get_time_series_features [summary]

        Returns:
            np.ndarray: [description]
        """
        cancellation_statistics = [
            operator(self._exchange.received_cancellations)
            for operator in self._cancellation_operators
        ]

        match_statistics = [
            operator(self._exchange.matches) for operator in self._match_operators
        ]

        order_statistics = [
            operator(self._exchange.received_orders)
            for operator in self._order_operators
        ]

        return np.hstack((cancellation_statistics, match_statistics, order_statistics))
