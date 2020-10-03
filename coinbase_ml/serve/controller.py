"""
Queries the model server and Coinbase API.
Basically ties everything together.
"""
# pylint: disable=no-name-in-module,import-error
from datetime import datetime, timedelta
from itertools import count
import logging
from time import sleep
from typing import Optional, Type

import requests
from ray.rllib.utils.policy_client import PolicyClient

import coinbase_ml.common.constants as cc
from coinbase_ml.common.action import ActionBase
from coinbase_ml.common.actionizers import Actionizer
from coinbase_ml.common.featurizers import Featurizer
from coinbase_ml.serve.metrics_recorder import (
    Metrics,
    MetricsRecorder,
    convert_to_sacred_log_format,
)
from coinbase_ml.common.reward import REWARD_STRATEGIES, BaseRewardStrategy
from coinbase_ml.common.utils import parse_time_delta
from coinbase_ml.common.utils.sacred_utils import (
    get_sacred_experiment_results,
    log_metrics_to_sacred,
)
from coinbase_ml.fakebase.utils.exceptions import ExchangeFinishedException
from coinbase_ml.serve import constants as c
from coinbase_ml.serve.account import Account
from coinbase_ml.serve.exchange import Exchange
from coinbase_ml.serve.experiment_configs.common import SACRED_EXPERIMENT

LOGGER = logging.getLogger(__name__)


class Controller:
    """
    Controller
    """

    def __init__(
        self,
        end_dt: datetime,
        num_time_steps: int,
        num_warmup_time_steps: int,
        result_metric: Metrics,
        reward_strategy: Type[BaseRewardStrategy],
        start_dt: datetime,
        time_delta: timedelta,
    ) -> None:
        """
        __init__ [summary]

        Args:
            end_dt (datetime): [description]
            num_time_steps (int): [description]
            num_warmup_time_steps (int): [description]
            result_metric (Metrics): [description]
            reward_strategy (Type[BaseRewardStrategy]): [description]
            start_dt (datetime): [description]
            time_delta (timedelta): [description]
        """

        self.client = PolicyClient(
            f"http://{c.SERVED_POLICY_ADDRESS}:{c.SERVED_POLICY_PORT}"
        )
        self.exchange = Exchange(
            product_id=cc.PRODUCT_ID,
            start_dt=start_dt,
            end_dt=end_dt,
            time_delta=time_delta,
        )
        self.account = Account(self.exchange)
        self.exchange.account = self.account

        self.num_warmup_time_steps = num_warmup_time_steps
        self.featurizer = Featurizer(
            exchange=self.exchange,
            reward_strategy=reward_strategy,
            state_buffer_size=num_time_steps,
        )
        self.metrics_recorder: Optional[MetricsRecorder] = None
        self.time_delta = time_delta
        self.result_metric = result_metric

    def _step(self, action: ActionBase) -> None:
        """
        _step [summary]
        """
        sleep(self.time_delta.total_seconds())
        self.featurizer.update_state_buffer(action)
        self.exchange.step()
        action.cancel_expired_orders(current_dt=self.exchange.interval_start_dt)

    def _warmup(self) -> None:
        """
        _warmup [summary]
        """
        LOGGER.info(
            "Warming up the Exchange for %s time steps.", self.num_warmup_time_steps
        )
        actionizer = Actionizer[Account](self.exchange.account)
        no_transaction = actionizer.get_action()
        for _ in range(self.num_warmup_time_steps):
            self._step(no_transaction)

        self.metrics_recorder = MetricsRecorder(self.featurizer)

    def run(self) -> float:
        """
        run [summary]

        Returns:
            float: [description]
        """
        wait_time = 5
        for _ in range(20):
            try:
                episode_id: str = self.client.start_episode(training_enabled=False)
                break
            except requests.exceptions.ConnectionError:
                LOGGER.error(
                    "Unable to connect to model server. Trying again in %s seconds.",
                    wait_time,
                )
                sleep(wait_time)

        self.exchange.run()
        self._warmup()

        try:
            for i in count():
                observation = self.featurizer.get_observation()

                prediction = self.client.get_action(episode_id, observation)
                actionizer = Actionizer[Account](self.exchange.account, prediction)
                action = actionizer.get_action()
                action.cancel_expired_orders(self.exchange.interval_start_dt)
                action.execute()

                reward = self.featurizer.calculate_reward()

                self.client.log_returns(episode_id, reward)

                self.metrics_recorder.update_metrics()

                if i % c.METRICS_RECORD_FREQUENCY == 0:
                    log_metrics_to_sacred(
                        experiment=SACRED_EXPERIMENT,
                        metrics=convert_to_sacred_log_format(
                            self.metrics_recorder.calc_aggregates()
                        ),
                    )
                    self.metrics_recorder.reset()

                try:
                    self._step(action)
                except ExchangeFinishedException:
                    LOGGER.info("Serving run finished.")
                    self.client.end_episode(episode_id, observation)
                    break

        finally:
            self.exchange.stop()
            LOGGER.info("Canceling all remaining orders.")
            action.cancel_expired_orders(c.UTC_MAX)

        return float(
            self.metrics_recorder.get_metrics()[  # pylint: disable=unsubscriptable-object
                self.result_metric
            ]
        )


@SACRED_EXPERIMENT.automain
def main(
    result_metric: Metrics,
    serving_run_end_dt: datetime,
    serving_run_start_dt: datetime,
    time_delta: Optional[timedelta],
    train_experiment_id: int,
) -> float:
    """
    main is the entry point for the serve module

    Args:
        result_metric (Metrics): Metric that will be recorded as result in Sacred
        serving_run_end_dt (datetime): [description]
        serving_run_start_dt (datetime): [description]
        time_delta (Optional[timedelta]): [description]
        train_experiment_id (int): [description]

    Returns:
        float: [description]
    """
    train_experiment = get_sacred_experiment_results(train_experiment_id)

    controller = Controller(
        end_dt=serving_run_end_dt,
        num_time_steps=train_experiment.config["num_time_steps"],
        num_warmup_time_steps=train_experiment.config["num_warmup_time_steps"],
        result_metric=result_metric,
        reward_strategy=REWARD_STRATEGIES[
            train_experiment.config["reward_strategy_name"]
        ],
        start_dt=serving_run_start_dt,
        time_delta=(
            time_delta
            if time_delta
            else parse_time_delta(train_experiment.config["time_delta_str"])
        ),
    )

    return controller.run()
