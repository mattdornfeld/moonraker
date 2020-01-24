"""
time_series_featurizer
"""
from statistics import mean
from typing import Any, Callable, Generic, List, TypeVar

from funcy import compose, partial
import numpy as np

from fakebase.orm import CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder
from fakebase.types import OrderSide

from coinbase_ml.common.featurizers.types import Exchange
from coinbase_ml.common.utils import stdev
from coinbase_ml.common.utils.preprocessing_utils import NormalizedOperation

Event = TypeVar("Event", CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder)


class TimeSeriesFeaturizer(Generic[Exchange]):
    """
    TimeSeriesFeaturizer
    """

    def __init__(self, exchange: Exchange) -> None:
        """
        __init__ [summary]

        Args:
            exchange (Exchange): [description]
        """
        self.exchange = exchange
        self.cancellation_operators: List[NormalizedOperation] = []
        self.match_operators: List[NormalizedOperation] = []
        self.order_operators: List[NormalizedOperation] = []
        self._create_time_series_operators()

    @staticmethod
    def _calc_event_counts(order_side: OrderSide, events: List[Event]) -> int:
        """Summary

        Args:
            order_side (OrderSide): Description
            events (List[Event]): Description

        Returns:
            int: Description
        """
        return len([e for e in events if e.side == order_side])

    def _calc_op_for_attribute(
        self,
        attribute: str,
        operation: Callable[[List[Any]], float],
        order_side: OrderSide,
        events: List[Event],
    ) -> float:
        """Summary
        Args:
            attribute (str): Description
            operation (Callable[[List[Any]], float]): Description
            order_side (OrderSide): Description
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
        for order_side in [OrderSide.buy, OrderSide.sell]:

            operator = partial(self._calc_event_counts, order_side)

            name = f"{order_side}_count"
            self.cancellation_operators.append(NormalizedOperation(operator, name))
            self.match_operators.append(NormalizedOperation(operator, name))
            self.order_operators.append(NormalizedOperation(operator, name))

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
                        self.cancellation_operators.append(
                            NormalizedOperation(operator, name)
                        )

                    self.match_operators.append(NormalizedOperation(operator, name))
                    self.order_operators.append(NormalizedOperation(operator, name))

    @staticmethod
    def _filter_events(
        attribute: str, events: List[Event], order_side: OrderSide
    ) -> List[Any]:
        """Summary
        Args:
            attribute (OrderSide): Description
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
            operator(self.exchange.received_cancellations)
            for operator in self.cancellation_operators
        ]

        match_statistics = [
            operator(self.exchange.matches) for operator in self.match_operators
        ]

        order_statistics = [
            operator(self.exchange.received_orders) for operator in self.order_operators
        ]

        return np.hstack((cancellation_statistics, match_statistics, order_statistics))

    @property
    def operators(self) -> List[NormalizedOperation]:
        """
        operators is a list

        Returns:
            List[NormalizedOperation]: [description]
        """
        return self.cancellation_operators + self.match_operators + self.order_operators
