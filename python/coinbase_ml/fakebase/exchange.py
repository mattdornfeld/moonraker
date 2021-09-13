"""Simulates the Coinbase Pro exchange
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from time import sleep
from typing import TYPE_CHECKING, Dict, List, Optional, cast

import numpy as np
import pandas as pd
from dateutil import parser
from google.protobuf.duration_pb2 import Duration
from grpc._channel import _InactiveRpcError as InactiveRpcError
from nptyping import NDArray

import coinbase_ml.fakebase.account as account
from coinbase_ml.common import constants as cc
from coinbase_ml.common.observations import Observation
from coinbase_ml.common.protos.actionizers_pb2 import ActionizerConfigs
from coinbase_ml.common.protos.environment_pb2 import (
    ActionRequest,
    ObservationRequest,
    RewardRequest,
)
from coinbase_ml.common.protos.featurizers_pb2 import FeaturizerConfigs
from coinbase_ml.common.protos.events_pb2 import SimulationId as SimulationIdProto
from coinbase_ml.common.types import SimulationId, FeatureName
from coinbase_ml.common.utils.arrow_utils import read_from_arrow_socket
from coinbase_ml.fakebase import constants as c
from coinbase_ml.fakebase.orm import CoinbaseCancellation, CoinbaseMatch, CoinbaseOrder
from coinbase_ml.fakebase.protos import fakebase_pb2  # pylint: disable=unused-import
from coinbase_ml.fakebase.protos.fakebase_pb2 import (
    ExchangeInfo,
    OrderBooksRequest,
    OrderBooks,
    PopulateStorageRequest,
    SimulationInfo,
    SimulationStartRequest,
    SimulationType,
    StepRequest,
    RunRequest,
    PopulateStorageParameters,
    DatabaseBackend,
)
from coinbase_ml.fakebase.protos.fakebase_pb2_grpc import ExchangeServiceStub
from coinbase_ml.fakebase.types import (
    BinnedOrderBook,
    OrderSide,
    ProductId,
    ProductVolume,
    QuoteVolume,
)
from coinbase_ml.fakebase.utils.exceptions import ExchangeFinishedException
from coinbase_ml.fakebase.utils.grpc_utils import (
    create_channel,
    get_random_free_port,
    start_fakebase_server,
    port_in_use,
)

if TYPE_CHECKING:
    import coinbase_ml.common.protos.actionizers_pb2 as actionizers_pb2
    import coinbase_ml.common.protos.featurizers_pb2 as featurizers_pb2
    import coinbase_ml.common.protos.environment_pb2 as environment_pb2


@dataclass
class SimulationMetadata:
    account: account.Account
    actionizer: "actionizers_pb2.ActionizerValue"
    end_dt: datetime
    observation_request: ObservationRequest
    product_id: ProductId
    simulation_id: SimulationId
    simulation_info: SimulationInfo
    start_dt: datetime
    time_delta: timedelta

    @property
    def exchange_info(self) -> ExchangeInfo:
        return self.simulation_info.exchangeInfo


class Exchange:
    def __init__(
        self,
        port: Optional[int] = None,
        test_mode: bool = False,
        health_check: bool = True,
        terminate_automatically: bool = True,
    ) -> None:
        port = get_random_free_port() if port is None else port
        self._simulation_metadatas: Dict[SimulationId, SimulationMetadata] = {}
        self.fakebase_server_process = (
            start_fakebase_server(port, test_mode, terminate_automatically)
            if not port_in_use(port)
            else None
        )
        self.channel = create_channel(port)
        self.stub = ExchangeServiceStub(self.channel)
        if health_check:
            self._exchange_server_health_check()

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

    @staticmethod
    def _date_time_to_proto_str(date_time: datetime) -> str:
        return date_time.isoformat() + "Z"

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
                self.stub.getSimulationIds(c.EMPTY_PROTO)
            except InactiveRpcError as inactive_rpc_error:
                if i >= max_tries:
                    raise inactive_rpc_error

                sleep(0.3)
                continue

    @staticmethod
    def _generate_action_request(
        actionizer: "actionizers_pb2.ActionizerValue",
        simulation_id: Optional[SimulationId],
        actor_output: NDArray[float],
    ) -> ActionRequest:
        return ActionRequest(
            actorOutput=list(actor_output),
            actionizer=actionizer,
            simulationId=Exchange._generate_simulation_id_proto(simulation_id),
        )

    @staticmethod
    def _generate_observation_request(
        featurizer: "featurizers_pb2.FeaturizerValue",
        featurizer_configs: FeaturizerConfigs,
        reward_strategy: "environment_pb2.RewardStrategyValue",
        simulation_id: Optional[SimulationId] = None,
    ) -> ObservationRequest:
        return ObservationRequest(
            normalize=False,
            rewardRequest=RewardRequest(rewardStrategy=reward_strategy),
            simulationId=Exchange._generate_simulation_id_proto(simulation_id),
            featurizer=featurizer,
            featurizerConfigs=featurizer_configs,
        )

    @staticmethod
    def _generate_order_book_request(simulation_id: SimulationId) -> OrderBooksRequest:
        return OrderBooksRequest(
            orderBookDepth=cc.ORDER_BOOK_DEPTH,
            simulationId=Exchange._generate_simulation_id_proto(simulation_id),
        )

    @staticmethod
    def _generate_simulation_id_proto(simulation_id: SimulationId) -> SimulationIdProto:
        return SimulationIdProto(simulationId=simulation_id)

    @staticmethod
    def _time_delta_to_duration(time_delta: timedelta) -> Duration:
        return Duration(seconds=int(time_delta.total_seconds()))

    def _update_simulation_metadata(self, simulation_info: SimulationInfo) -> None:
        simulation_metadata = self.simulation_metadata(
            SimulationId(simulation_info.simulationId.simulationId)
        )
        simulation_metadata.simulation_info = simulation_info
        simulation_metadata.account.account_info = (
            simulation_info.exchangeInfo.accountInfo
        )

    def account(self, simulation_id: SimulationId) -> account.Account:
        return self._simulation_metadatas[simulation_id].account

    def bin_order_book_by_price(
        self, order_side: OrderSide, simulation_id: SimulationId
    ) -> BinnedOrderBook:
        order_books: OrderBooks = self.stub.getOrderBooks(
            self._generate_order_book_request(simulation_id)
        )

        return self._bin_order_books_by_price(order_books)[order_side]

    def finished(self, simulation_id: SimulationId) -> bool:
        time_delta = self.simulation_metadata(simulation_id).time_delta
        end_dt = self.simulation_metadata(simulation_id).end_dt
        return self.interval_end_dt(simulation_id) + time_delta >= end_dt

    def info_dict(self, simulation_id: SimulationId) -> Dict[str, float]:
        """Retrieves the InfoDict which contains summary information about the exchange
        """
        return dict(
            self.simulation_metadata(
                simulation_id
            ).simulation_info.observation.info.infoDict.infoDict
        )

    def interval_end_dt(self, simulation_id: SimulationId) -> datetime:
        return parser.parse(
            self.simulation_metadata(simulation_id).exchange_info.intervalEndTime
        ).replace(tzinfo=None)

    def interval_start_dt(self, simulation_id: SimulationId) -> datetime:
        return parser.parse(
            self.simulation_metadata(simulation_id).exchange_info.intervalStartTime
        ).replace(tzinfo=None)

    def matches(self, simulation_id: SimulationId) -> List[CoinbaseMatch]:
        match_events = self.stub.getMatches(simulation_id).matchEvents
        return [CoinbaseMatch.from_proto(m) for m in match_events]

    @staticmethod
    def observation(simulation_id: SimulationId) -> Observation:
        """Observation from latest step
        """
        return Observation.from_arrow_socket(simulation_id)

    @staticmethod
    def features(simulation_id: SimulationId) -> Dict[FeatureName, pd.Series]:
        """Features from latest step
        """
        arrow_socket_file = cc.ARROW_SOCKETS_BASE_DIR / f"{simulation_id}.socket"
        return cast(
            Dict[FeatureName, pd.Series],
            read_from_arrow_socket(arrow_socket_file)[0].to_dict("series"),
        )

    def populate_storage(
        self,
        start_time: datetime,
        end_time: datetime,
        time_delta: timedelta,
        backup_to_cloud_storage: bool = True,
        database_backend: "fakebase_pb2.DatabaseBackendValue" = DatabaseBackend.BigQuery,
    ) -> None:
        populate_storage_parameters = PopulateStorageParameters(
            startTime=self._date_time_to_proto_str(start_time),
            endTime=self._date_time_to_proto_str(end_time),
            timeDelta=self._time_delta_to_duration(time_delta),
        )
        populate_storage_request = PopulateStorageRequest(
            populateStorageParameters=[populate_storage_parameters],
            ingestToLocalStorage=True,
            backupToCloudStorage=backup_to_cloud_storage,
            databaseBackend=database_backend,
        )
        self.stub.populateStorage(populate_storage_request)

    def reward(self, simulation_id: SimulationId) -> float:
        """Reward from latest step
        """
        return self.simulation_metadata(
            simulation_id
        ).simulation_info.observation.reward.reward

    def simulation_metadata(self, simulation_id: SimulationId) -> SimulationMetadata:
        return self._simulation_metadatas[simulation_id]

    def start(
        self,
        actionizer: "actionizers_pb2.ActionizerValue",
        end_dt: datetime,
        featurizer: "featurizers_pb2.FeaturizerValue",
        initial_product_funds: ProductVolume,
        initial_quote_funds: QuoteVolume,
        num_warmup_time_steps: int,
        product_id: ProductId,
        reward_strategy: "environment_pb2.RewardStrategyValue",
        start_dt: datetime,
        time_delta: timedelta,
        backup_to_cloud_storage: bool = False,
        actionizer_configs: ActionizerConfigs = ActionizerConfigs(),
        featurizer_configs: FeaturizerConfigs = FeaturizerConfigs(),
        enable_progress_bar: bool = False,
        simulation_type: "fakebase_pb2.SimulationTypeValue" = SimulationType.evaluation,
        skip_database_query: bool = False,
        skip_checkpoint_after_warmup: bool = False,
    ) -> SimulationMetadata:
        """Start a simulation
        """
        action_request = self._generate_action_request(actionizer, None, np.array([]))
        observation_request = self._generate_observation_request(
            featurizer, featurizer_configs, reward_strategy
        )
        simulation_start_request = SimulationStartRequest(
            actionRequest=action_request,
            actionizerConfigs=actionizer_configs,
            backupToCloudStorage=backup_to_cloud_storage,
            databaseBackend=c.DATABASE_BACKEND,
            enableProgressBar=enable_progress_bar,
            endTime=self._date_time_to_proto_str(end_dt),
            initialProductFunds=str(initial_product_funds),
            initialQuoteFunds=str(initial_quote_funds),
            numWarmUpSteps=num_warmup_time_steps,
            observationRequest=observation_request,
            simulationType=simulation_type,
            skipCheckpointAfterWarmup=skip_checkpoint_after_warmup,
            skipDatabaseQuery=skip_database_query,
            startTime=self._date_time_to_proto_str(start_dt),
            timeDelta=self._time_delta_to_duration(time_delta),
        )

        simulation_info: SimulationInfo = self.stub.start(simulation_start_request)
        simulation_id = SimulationId(simulation_info.simulationId.simulationId)
        simulation_metadata = SimulationMetadata(
            account=account.Account(
                self.channel, simulation_info.exchangeInfo.accountInfo, simulation_id
            ),
            actionizer=actionizer,
            end_dt=end_dt,
            observation_request=self._generate_observation_request(
                featurizer, featurizer_configs, reward_strategy, simulation_id
            ),
            product_id=product_id,
            simulation_id=SimulationId(simulation_info.simulationId.simulationId),
            simulation_info=simulation_info,
            start_dt=start_dt,
            time_delta=time_delta,
        )
        self._simulation_metadatas[simulation_id] = simulation_metadata

        return simulation_metadata

    def reset(self, simulation_id: SimulationId) -> None:
        """
        Reset the exchange to the state created with `checkpoint`. Useful when
        doing multiple simulations that need to start from the same warmed up state.
        """
        observation_request = self.simulation_metadata(
            simulation_id
        ).observation_request
        simulation_info: SimulationInfo = self.stub.reset(observation_request)
        self._update_simulation_metadata(simulation_info)

    def run(self, simulation_id: SimulationId) -> None:
        simulation_metadata = self.simulation_metadata(simulation_id)
        actionizer = simulation_metadata.actionizer

        run_request = RunRequest(
            simulationId=SimulationIdProto(simulationId=simulation_id),
            observationRequest=simulation_metadata.observation_request,
            actionRequest=self._generate_action_request(
                actionizer, simulation_id, np.array([])
            ),
        )

        simulation_info: SimulationInfo = self.stub.run(run_request)
        self._update_simulation_metadata(simulation_info)

    def shutdown(self) -> None:
        self.stub.shutdown(c.EMPTY_PROTO)

    def step(
        self,
        simulation_id: SimulationId,
        insert_cancellations: Optional[List[CoinbaseCancellation]] = None,
        insert_orders: Optional[List[CoinbaseOrder]] = None,
        actor_output: Optional[NDArray[float]] = None,
    ) -> None:
        if self.finished(simulation_id):
            raise ExchangeFinishedException

        _insert_cancellations = (
            [] if insert_cancellations is None else insert_cancellations
        )
        _insert_orders = [] if insert_orders is None else insert_orders

        simulation_metadata = self.simulation_metadata(simulation_id)
        actionizer = simulation_metadata.actionizer
        action_request = (
            self._generate_action_request(actionizer, simulation_id, actor_output)
            if actor_output is not None
            else None
        )
        step_request = StepRequest(
            insertOrders=[order.to_proto() for order in _insert_orders],
            insertCancellations=[
                cancellation.to_proto() for cancellation in _insert_cancellations
            ],
            observationRequest=simulation_metadata.observation_request,
            actionRequest=action_request,
            simulationId=Exchange._generate_simulation_id_proto(simulation_id),
        )

        simulation_info: SimulationInfo = self.stub.step(step_request)
        self._update_simulation_metadata(simulation_info)

    def stop(self, simulation_id: SimulationId) -> None:
        self.stub.stop(self._generate_simulation_id_proto(simulation_id))
