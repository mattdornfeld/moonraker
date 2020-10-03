"""
Base classes for Account and Exchange
"""
from __future__ import annotations
from datetime import datetime, timedelta
from typing import TYPE_CHECKING, Generic, List, Optional, TypeVar

from nptyping import NDArray

from coinbase_ml.common.utils.time_utils import TimeInterval
from coinbase_ml.fakebase.orm import CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder
from coinbase_ml.fakebase.utils.exceptions import ExchangeFinishedException
from coinbase_ml.fakebase.types import BinnedOrderBook, OrderSide, ProductId

if TYPE_CHECKING:
    # To avoid circular import account on the submodule level
    # AccountBase is then referenced using the str syntax below
    import coinbase_ml.fakebase.base_classes.account

Account = TypeVar("Account", bound="coinbase_ml.fakebase.base_classes.AccountBase")


class ExchangeBase(Generic[Account]):
    """
    ExchangeBase abstract class
    """

    def __init__(
        self,
        end_dt: datetime,
        product_id: ProductId,
        start_dt: datetime,
        time_delta: timedelta,
    ) -> None:
        self._account: Optional[Account] = None
        self.end_dt = end_dt
        self.product_id = product_id
        self.start_dt = start_dt
        self.time_delta = time_delta

        self.interval_start_dt = start_dt - time_delta
        self._interval_end_dt = start_dt

    @property
    def account(self) -> Optional[Account]:
        """
        account [summary]

        Returns:
            Optional[Account]: [description]
        """
        return self._account

    @account.setter
    def account(self, value: Account) -> None:
        """
        account [summary]

        Args:
            value (Account): [description]
        """
        self._account = value

    def bin_order_book_by_price(self, order_side: OrderSide) -> BinnedOrderBook:
        """
        bin_order_book_by_price [summary]

        Args:
            order_side (OrderSide): [description]

        Raises:
            NotImplementedError: [description]

        Returns:
            BinnedOrderBook: [description]
        """
        raise NotImplementedError

    @property
    def finished(self) -> bool:
        """Returns True if we reached the end of the training period.
        False if not.

        Returns:
            bool: Description
        """

        return self.interval_end_dt >= self.end_dt

    @property
    def interval_end_dt(self) -> datetime:
        """
        interval_end_dt [summary]

        Returns:
            datetime: [description]
        """
        return self._interval_end_dt

    @interval_end_dt.setter
    def interval_end_dt(self, value: datetime) -> None:
        """
        interval_end_dt [summary]

        Args:
            value (datetime): [description]
        """
        self._interval_end_dt = value

    @property
    def matches(self) -> List[CoinbaseMatch]:
        """
        matches [summary]

        Raises:
            NotImplementedError: [description]

        Returns:
            List[CoinbaseMatch]: [description]
        """
        raise NotImplementedError

    @property
    def received_cancellations(self) -> List[CoinbaseCancellation]:
        """
        received_cancellations [summary]

        Raises:
            NotImplementedError: [description]

        Returns:
            CoinbaseEvent: [description]
        """
        raise NotImplementedError

    @property
    def received_orders(self) -> List[CoinbaseOrder]:
        """
        received_orders [summary]

        Raises:
            NotImplementedError: [description]

        Returns:
            CoinbaseEvent: [description]
        """
        raise NotImplementedError

    # pylint: disable=unused-argument
    def step(
        self,
        insert_cancellations: Optional[List[CoinbaseCancellation]] = None,
        insert_orders: Optional[List[CoinbaseOrder]] = None,
        actor_output: Optional[NDArray[float]] = None,
    ) -> None:
        """
        step Abstract method that advances state of exchange.
        Make sure to call this in subclasses before self.interval_end_dt
        is updated.

        Args:
            insert_cancellations (Optional[List[CoinbaseCancellation]], optional): Defaults to None
            insert_orders (Optional[List[CoinbaseOrder]], optional): Defaults to None

        Raises:
            ExchangeFinishedException: [description]
        """
        if self.finished:
            raise ExchangeFinishedException

    @property
    def step_interval(self) -> TimeInterval:
        """
        step_interval [summary]

        Returns:
            TimeInterval: [description]
        """
        return TimeInterval(self._interval_end_dt, self.interval_start_dt)
