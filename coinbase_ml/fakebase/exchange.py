"""Simulates the Coinbase Pro exchange
"""
from __future__ import annotations
from datetime import datetime, timedelta
from time import sleep
from typing import Any, Dict, List, Optional

from dateutil import parser
from google.protobuf.duration_pb2 import Duration
from grpc._channel import _InactiveRpcError as InactiveRpcError

import coinbase_ml.fakebase.account as _account
from coinbase_ml.common import constants as cc
from coinbase_ml.common.featurizers.protos.featurizer_pb2 import (
    Observation as ObservationProto,
    ObservationRequest,
    RewardRequest,
    RewardStrategy,
)
from coinbase_ml.common.observations import Observation
from coinbase_ml.fakebase.protos.fakebase_pb2 import (
    ExchangeInfo,
    OrderBooksRequest,
    OrderBooks,
    SimulationInfo,
    SimulationInfoRequest,
    SimulationStartRequest,
    StepRequest,
)
from coinbase_ml.fakebase.protos.fakebase_pb2_grpc import ExchangeServiceStub
from coinbase_ml.fakebase import constants as c
from coinbase_ml.fakebase.base_classes.exchange import ExchangeBase
from coinbase_ml.fakebase.orm import CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder
from coinbase_ml.fakebase.types import (
    BinnedOrderBook,
    OrderSide,
    ProductId,
    ProductVolume,
    QuoteVolume,
)
from coinbase_ml.fakebase.utils.grpc_utils import (
    create_channel,
    get_random_free_port,
    start_fakebase_server,
)


class Exchange(ExchangeBase[_account.Account]):  # pylint: disable=R0903,R0902
    """Summary
    """

    def __init__(
        self,
        end_dt: datetime,
        product_id: ProductId,
        start_dt: datetime,
        time_delta: timedelta,
        create_exchange_process: bool = True,
        test_mode: bool = False,
    ) -> None:
        """
        __init__ [summary]

        Args:
            end_dt (datetime): [description]
            product_id (ProductId): [description]
            start_dt (datetime): [description]
            time_delta (timedelta): [description]
            create_exchange_process (bool): [description]
        """
        super().__init__(end_dt, product_id, start_dt, time_delta)

        self._simulation_info_request = SimulationInfoRequest()
        self._observation = ObservationProto()

        if create_exchange_process:
            port = get_random_free_port()
            self.fakebase_server_process = start_fakebase_server(port, test_mode)
        else:
            port = c.FAKBASE_SERVER_DEFAULT_PORT
            self.fakebase_server_process = None

        self.channel = create_channel(port)
        self.stub = ExchangeServiceStub(self.channel)
        self.account = _account.Account(self)
        self._exchange_server_health_check()

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ [summary]

        Args:
            other (Any): [description]

        Returns:
            bool: [description]
        """
        if isinstance(other, Exchange):
            _other: Exchange = other
            return_val = (
                self.end_dt == _other.end_dt
                and self.interval_end_dt == _other.interval_end_dt
                and self.interval_start_dt == _other.interval_start_dt
                and self.time_delta == _other.time_delta
                and self.account == _other.account
            )
        else:
            raise TypeError

        return return_val

    @staticmethod
    def _bin_order_books_by_price(
        order_books: OrderBooks,
    ) -> Dict[OrderSide, BinnedOrderBook]:
        return {
            OrderSide.buy: {
                cc.PRODUCT_ID.price_type(price): cc.PRODUCT_ID.product_volume_type(
                    volume
                )
                for (price, volume) in order_books.buyOrderBook.items()
            },
            OrderSide.sell: {
                cc.PRODUCT_ID.price_type(price): cc.PRODUCT_ID.product_volume_type(
                    volume
                )
                for (price, volume) in order_books.sellOrderBook.items()
            },
        }

    def _exchange_server_health_check(self) -> None:
        """
        _exchange_server_health_check blocks until fakebase_server_process is healthy or
        health check retry limit is reached

        Raises:
            InactiveRpcError
        """
        max_tries = 100
        for i in range(max_tries + 1):
            try:
                self.stub.getExchangeInfo(c.EMPTY_PROTO)
            except InactiveRpcError as inactive_rpc_error:
                if i >= max_tries:
                    raise inactive_rpc_error

                sleep(0.3)
                continue

    @staticmethod
    def _generate_observation_request() -> ObservationRequest:
        reward_request = RewardRequest(
            rewardStrategy=RewardStrategy.LogReturnRewardStrategy
        )
        return ObservationRequest(
            orderBookDepth=cc.ORDER_BOOK_DEPTH,
            normalize=False,
            rewardRequest=reward_request,
        )

    @staticmethod
    def _generate_order_book_request() -> OrderBooksRequest:
        return OrderBooksRequest(orderBookDepth=cc.ORDER_BOOK_DEPTH)

    @classmethod
    def _generate_simulation_info_request(cls) -> SimulationInfoRequest:
        return SimulationInfoRequest(
            observationRequest=cls._generate_observation_request(),
        )

    def _update_exchange_info(self, exchange_info: ExchangeInfo) -> None:
        """
        _update_exchange_info [summary]

        Args:
            exchange_info (ExchangeInfo): [description]
        """
        self.account.account_info = exchange_info.accountInfo
        self.interval_start_dt = parser.parse(exchange_info.intervalStartTime).replace(
            tzinfo=None
        )
        self._interval_end_dt = parser.parse(exchange_info.intervalEndTime).replace(
            tzinfo=None
        )

    def _update_simulation_info(self, simulation_info: SimulationInfo) -> None:
        self._observation = simulation_info.observation
        self._update_exchange_info(simulation_info.exchangeInfo)

    @property
    def account(self) -> _account.Account:
        """
        account [summary]

        Returns:
            _account.Account: [description]
        """
        return self._account

    @account.setter
    def account(self, value: _account.Account) -> None:
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
            order_side (OrderSide)

        Returns:
            BinnedOrderBook
        """
        order_books: OrderBooks = self.stub.getOrderBooks(
            self._generate_order_book_request()
        )

        return self._bin_order_books_by_price(order_books)[order_side]

    def checkpoint(self) -> None:
        """Saves the current state of the Fakebase Exchange server
        """
        self.stub.checkpoint(c.EMPTY_PROTO)

    @property
    def info_dict(self) -> Dict[str, float]:
        """Retrieves the InfoDict which contains summary information about the exchange
        """
        return dict(self._observation.infoDict.infoDict)

    @property
    def matches(self) -> List[CoinbaseMatch]:
        """
        matches [summary]

        Returns:
            List[CoinbaseMatch]: [description]
        """
        match_events = self.stub.getMatches(c.EMPTY_PROTO).matchEvents
        return [CoinbaseMatch.from_proto(m) for m in match_events]

    @property
    def observation(self) -> Observation:
        """Observation from latest step
        """
        return Observation.from_proto(self._observation)

    @property
    def received_cancellations(self) -> List[CoinbaseCancellation]:
        """
        received_cancellations [summary]

        Returns:
            CoinbaseEvent: [description]
        """
        return []

    @property
    def received_orders(self) -> List[CoinbaseOrder]:
        """
        received_orders [summary]

        Returns:
            CoinbaseEvent: [description]
        """
        return []

    @received_orders.setter
    def received_orders(self, value: List[CoinbaseOrder]) -> None:
        """
        received_orders [summary]

        Args:
            value (List[CoinbaseEvent]): [description]
        """
        self._received_orders = value

    @property
    def reward(self) -> float:
        """Reward from latest step
        """
        return self._observation.reward.reward

    def start(
        self,
        initial_product_funds: ProductVolume,
        initial_quote_funds: QuoteVolume,
        num_warmup_time_steps: int,
        snapshot_buffer_size: int,
    ) -> None:
        """
        start [summary]

        Args:
            initial_product_funds (ProductVolume): [description]
            initial_quote_funds (QuoteVolume): [description]
            num_warmup_time_steps (int): [description]
            snapshot_buffer_size (int): [description]
        """
        self._simulation_info_request = self._generate_simulation_info_request()

        message = SimulationStartRequest(
            startTime=self.start_dt.isoformat() + "Z",
            endTime=self.end_dt.isoformat() + "Z",
            timeDelta=Duration(seconds=int(self.time_delta.total_seconds())),
            numWarmUpSteps=0,
            initialProductFunds=str(initial_product_funds),
            initialQuoteFunds=str(initial_quote_funds),
            simulationInfoRequest=self._simulation_info_request,
            snapshotBufferSize=snapshot_buffer_size,
        )

        simulation_info: SimulationInfo = self.stub.start(message)
        self._update_simulation_info(simulation_info)

        for _ in range(num_warmup_time_steps):
            self.step()

        if num_warmup_time_steps > 0:
            self.stub.checkpoint(c.EMPTY_PROTO)

    def reset(self) -> None:
        """
        Reset the exchange to the state created with `checkpoint`. Useful when
        doing multiple simulations that need to start from the same warmed up state.
        """
        simulation_info: SimulationInfo = self.stub.reset(self._simulation_info_request)
        self._update_simulation_info(simulation_info)

    def step(
        self,
        insert_cancellations: Optional[List[CoinbaseCancellation]] = None,
        insert_orders: Optional[List[CoinbaseOrder]] = None,
    ) -> None:
        """
        step advances the exchange by time increment self.time_delta.
        Increments self.interval_start_dt and self.interval_end_dt by
        self.time_delta. Loads new orders and cancellations from
        DatabaseWorkers.results_queue. Goes through simulated exchange logic
        to updated self.matches and self.order_book.

        Args:
            insert_cancellations (Optional[List[CoinbaseCancellation]], optional): Defaults to None
            insert_orders (Optional[List[CoinbaseOrder]], optional): Defaults to None
        """
        super().step(insert_cancellations, insert_orders)
        _insert_cancellations = (
            [] if insert_cancellations is None else insert_cancellations
        )
        _insert_orders = [] if insert_orders is None else insert_orders

        step_request = StepRequest(
            insertOrders=[order.to_proto() for order in _insert_orders],
            insertCancellations=[
                cancellation.to_proto() for cancellation in _insert_cancellations
            ],
            simulationInfoRequest=self._simulation_info_request,
        )

        simulation_info: SimulationInfo = self.stub.step(step_request)
        self._update_simulation_info(simulation_info)

        self.account.placed_cancellations.clear()
        self.account.placed_orders.clear()

    def stop(self) -> None:
        """
        stop [summary]
        """
        self.stub.stop(c.EMPTY_PROTO)
