"""
Queries the model server and Coinbase API.
Basically ties everything together.
"""
from datetime import datetime, timedelta
from itertools import count
import logging
from time import sleep
from typing import Optional, Type

from funcy import compose
from ray.rllib.utils.policy_client import PolicyClient

from fakebase.utils import ExchangeFinishedException


from coinbase_ml.common.action import Action, ActionExecutor
from coinbase_ml.common.featurizers import Featurizer
from coinbase_ml.common.metrics import MetricsRecorder, convert_to_sacred_log_format
from coinbase_ml.common.reward import REWARD_STRATEGIES, BaseRewardStrategy
from coinbase_ml.common.utils import parse_time_delta
from coinbase_ml.common.utils.sacred_utils import (
    get_sacred_experiment_results,
    log_metrics_to_sacred,
)
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
            reward_strategy (Type[BaseRewardStrategy]): [description]
            start_dt (datetime): [description]
            time_delta (timedelta): [description]
        """

        self.account = Account()
        self.action_executor = ActionExecutor[Account](self.account)
        self.client = PolicyClient(
            f"http://{c.SERVED_POLICY_ADDRESS}:{c.SERVED_POLICY_PORT}"
        )
        self.exchange = Exchange(
            start_dt=start_dt, end_dt=end_dt, time_delta=time_delta,
        )
        self.exchange.account = self.account

        self.num_warmup_time_steps = num_warmup_time_steps
        self.featurizer = Featurizer(
            exchange=self.exchange,
            reward_strategy=reward_strategy,
            state_buffer_size=num_time_steps,
        )
        self.metrics_recorder: Optional[MetricsRecorder] = None
        self.time_delta = time_delta

    def _step(self) -> None:
        """
        _step [summary]
        """
        sleep(self.time_delta.total_seconds())
        self.featurizer.update_state_buffer()
        self.exchange.step()
        self.action_executor.cancel_expired_orders(
            current_dt=self.exchange.interval_start_dt
        )

    def _warmup(self) -> None:
        """
        _warmup [summary]
        """
        LOGGER.info(
            "Warming up the Exchange for %s time steps.", self.num_warmup_time_steps
        )
        for _ in range(self.num_warmup_time_steps):
            self._step()

        self.metrics_recorder = MetricsRecorder(self.featurizer)

    def run(self) -> float:
        """
        run [summary]

        Returns:
            float: [description]
        """
        episode_id: str = self.client.start_episode(training_enabled=False)
        self.exchange.run()
        self._warmup()

        try:
            for i in count():
                observation = self.featurizer.get_observation()

                action: Action = compose(
                    self.action_executor.translate_model_prediction_to_action,
                    self.client.get_action,
                )(episode_id, observation)

                self.action_executor.act(action)

                reward = self.featurizer.calculate_reward()

                self.client.log_returns(episode_id, reward)

                self.metrics_recorder.update_metrics()

                if i % c.METRICS_RECORD_FREQUENCY == 0:
                    log_metrics_to_sacred(
                        experiment=SACRED_EXPERIMENT,
                        metrics=convert_to_sacred_log_format(
                            self.metrics_recorder.calc_aggregates()
                        ),
                        prefix="serve",
                    )
                    self.metrics_recorder.reset()

                try:
                    self._step()
                except ExchangeFinishedException:
                    LOGGER.info("Serving run finished.")
                    self.client.end_episode(episode_id, observation)
                    break

        finally:
            self.exchange.stop()
            LOGGER.info("Canceling all remaining orders.")
            self.action_executor.cancel_expired_orders(c.UTC_MAX)

        return self.metrics_recorder.calc_roi()


@SACRED_EXPERIMENT.automain
def main(
    serving_run_end_dt: datetime,
    serving_run_start_dt: datetime,
    train_experiment_id: int,
    time_delta: Optional[timedelta] = None,
) -> float:
    """
    main is the entry point for the serve module

    Args:
        serving_run_end_dt (datetime): [description]
        serving_run_start_dt (datetime): [description]
        train_experiment_id (int): [description]

    Returns:
        float: [description]
    """
    train_experiment = get_sacred_experiment_results(train_experiment_id)

    controller = Controller(
        end_dt=serving_run_end_dt,
        num_time_steps=train_experiment.config["num_time_steps"],
        num_warmup_time_steps=train_experiment.config["num_warmup_time_steps"],
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