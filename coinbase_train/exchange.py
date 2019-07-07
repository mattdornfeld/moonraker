"""Summary
"""
from collections import defaultdict
from datetime import datetime, timedelta
from decimal import Decimal
from statistics import mean
from typing import Any, Callable, DefaultDict, Dict, List, TypeVar

from funcy import compose, partial
import numpy as np

from fakebase.exchange import Account as BaseAccount, Exchange as BaseExchange
from fakebase.orm import CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder

from coinbase_train import constants as c
from coinbase_train.utils import min_max_normalization, stdev, NormalizedOperation

Event = TypeVar('Event', CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder)

class Account(BaseAccount):

    """Summary
    """
    
    def get_account_state_as_array(self) -> Dict[str, np.ndarray]:
        """Summary
        
        Returns:
            Dict[str, np.ndarray]: Description
        """
        account_funds = self._get_funds_as_array()
        normalized_account_funds = (
            account_funds / 
            np.array([
                c.NORMALIZERS['USD_FUNDS'], 
                c.NORMALIZERS['USD_FUNDS'], 
                c.NORMALIZERS['BTC_FUNDS'], 
                c.NORMALIZERS['BTC_FUNDS']])
            )

        return dict(account_funds=account_funds,
                    normalized_account_funds=normalized_account_funds)

class Exchange(BaseExchange): #pylint: disable=W0223

    """Summary
    """
    def __init__(
            self, 
            end_dt: datetime, 
            num_workers: int, 
            start_dt: datetime, 
            time_delta: timedelta,
            add_account: bool = False,
            results_queue_size: int = 50):

        super().__init__(end_dt, num_workers, start_dt, time_delta, add_account, results_queue_size) 
        
        self._cancellation_operators: List[NormalizedOperation] = []
        self._match_operators: List[NormalizedOperation] = []
        self._order_operators: List[NormalizedOperation] = []
        for order_side in ['buy', 'sell']:

            operator = partial(
                self._calc_event_counts,
                order_side)

            name = f'{order_side}_count'
            self._cancellation_operators.append(NormalizedOperation(operator, name))
            self._match_operators.append(NormalizedOperation(operator, name))
            self._order_operators.append(NormalizedOperation(operator, name))

            for attribute in ['price', 'size']:
                for _operator in [mean, stdev]:
                    operator = partial(
                        self._calc_op_for_attribute, 
                        attribute, 
                        compose(float, _operator), 
                        order_side)

                    name = f'{order_side}_{attribute}_{_operator.__name__}'

                    if attribute in ['price']:
                        self._cancellation_operators.append(NormalizedOperation(operator, name))

                    self._match_operators.append(NormalizedOperation(operator, name))
                    self._order_operators.append(NormalizedOperation(operator, name))

        self.add_account(Account())

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

    @staticmethod
    def _calc_op_for_attribute(
            attribute: str, 
            operation: Callable[[List[Any]], float],  
            order_side: str,
            events: List[Event]) -> float:
        """Summary
        
        Args:
            attribute (str): Description
            operation (Callable[[List[Any]], float]): Description
            order_side (str): Description
            events (List[Event]): Description
        
        Returns:
            float: Description
        """
        filtered_events = Exchange._filter_events(attribute, events, order_side)

        return operation(filtered_events) if len(filtered_events) > 0 else 0.0

    @staticmethod
    def _filter_events(attribute: str, events: List[Event], order_side: str) -> List[Any]:
        """Summary
        
        Args:
            attribute (str): Description
            events (List[Event]): Description
            order_side (str): Description
        
        Returns:
            List[Any]: Description
        """
        return [getattr(e, attribute) for e in events if 
                e.side == order_side and 
                getattr(e, attribute) is not None]

    def _get_order_book_as_array(
            self, 
            order_side: str, 
            price_aggregation: Decimal = Decimal('0.01'), 
            price_normalization: float = 1.0, 
            size_normalization: float = 1.0) -> np.ndarray:
        """Summary
        
        Args:
            order_side (str): Description
            price_aggregation (Decimal, optional): Description
            price_normalization (Decimal, optional): Description
            size_normalization (Decimal, optional): Description
        
        Returns:
            np.ndarray: Description
        """
        price_volume_dict: DefaultDict[Decimal, Decimal] = defaultdict(Decimal)
        for _, order in self.order_book[order_side].items():
            binned_price = order.price.quantize(price_aggregation)
            price_volume_dict[binned_price] += order.remaining_size

        return (np.array([(min_max_normalization(price_normalization, 0, float(price)), 
                           min_max_normalization(size_normalization, 0, float(volume))) 
                          for price, volume in price_volume_dict.items()])
                if len(price_volume_dict) > 0 else np.zeros((1, 2)))
    
    def get_exchange_state_as_arrays(self) -> Dict[str, np.ndarray]:
        """[matches, received_orders, buy_order_book, sell_order_book, 
        account_orders, account_funds]
        
        Returns:
            'Dict[str, np.ndarray]': Description
        """
        cancellation_statistics = [operator(self.received_cancellations) for 
                                   operator in self._cancellation_operators]

        match_statistics = [operator(self.matches) for 
                            operator in self._match_operators]

        order_statistics = [operator(self.received_orders) for 
                            operator in self._order_operators]

        state = dict(
            buy_order_book=self._get_order_book_as_array(order_side='buy', 
                                                         price_aggregation=c.ORDER_BOOK_BIN_SIZE, 
                                                         price_normalization=c.NORMALIZERS['PRICE'], 
                                                         size_normalization=c.NORMALIZERS['SIZE']),
            cancellation_statistics=cancellation_statistics,
            closing_price=self.matches[-1].price if len(self.matches) > 0 else None,
            match_statistics=match_statistics, 
            order_statistics=order_statistics, 
            sell_order_book=self._get_order_book_as_array(order_side='sell', 
                                                          price_aggregation=c.ORDER_BOOK_BIN_SIZE, 
                                                          price_normalization=c.NORMALIZERS['PRICE'],  #pylint: disable=C0301
                                                          size_normalization=c.NORMALIZERS['SIZE']),
            )
        
        state.update(self.account.get_account_state_as_array())

        return state
