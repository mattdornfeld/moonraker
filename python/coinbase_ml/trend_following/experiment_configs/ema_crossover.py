# pylint: disable=unused-variable

from datetime import timedelta

from dateutil.parser import parse

from coinbase_ml.common.constants.tune import OptimizationMode, SearchAlgorithms
from coinbase_ml.common.protos.environment_pb2 import (
    InfoDictKey,
    RewardStrategy,
)
from coinbase_ml.common.protos.featurizers_pb2 import Featurizer
from coinbase_ml.common.utils.time_utils import (
    TimeInterval,
    generate_lookback_intervals,
)
from coinbase_ml.trend_following.experiment_configs.constants import SACRED_EXPERIMENT


@SACRED_EXPERIMENT.named_config
def ema_crossover_dev():
    actionizer_name = "coinbase_ml.common.actionizers.EmaCrossover"
    featurizer = Featurizer.Name(Featurizer.NoOp)
    featurizer_configs = {}
    optimize_time_intervals = [
        time_interval.to_str_tuple()
        for time_interval in generate_lookback_intervals(
            latest_time_interval=TimeInterval(
                start_dt=parse("2020-11-19 00:30:00.00"),
                end_dt=parse("2020-11-19 00:45:00.00"),
            ),
            num_lookback_intervals=2,
            lookback_timedelta=timedelta(minutes=15),
            reverse=False,
        )
    ]
    evaluate_time_intervals = [
        time_interval.to_str_tuple()
        for time_interval in generate_lookback_intervals(
            latest_time_interval=TimeInterval(
                start_dt=parse("2020-11-19 00:45:00.00"),
                end_dt=parse("2020-11-19 01:00:00.00"),
            ),
            num_lookback_intervals=2,
            lookback_timedelta=timedelta(minutes=15),
            reverse=False,
        )
    ]
    time_delta = 30
    initial_product_funds = "0.000000"
    initial_quote_funds = "10000.00"
    num_warmup_time_steps = 3
    result_metric = InfoDictKey.Name(InfoDictKey.portfolioValue)
    reward_strategy = RewardStrategy.Name(RewardStrategy.LogReturnRewardStrategy)

    num_samples = 10
    optimization_mode = OptimizationMode.MAX
    search_algorithm = SearchAlgorithms.HYPER_OPT_SEARCH
    search_algorithm_config = {"metric": result_metric, "mode": optimization_mode}
    tune_config = {
        "fastWindowSize": "tune.uniform(60, 480)",
        "slowWindowSize": "tune.uniform(60, 480)",
    }
    resources_per_trial = {"cpu": 4.0}


@SACRED_EXPERIMENT.named_config
def ema_crossover_staging():
    actionizer_name = "coinbase_ml.common.actionizers.EmaCrossover"
    featurizer = Featurizer.Name(Featurizer.NoOp)
    optimize_time_intervals = [
        time_interval.to_str_tuple()
        for time_interval in generate_lookback_intervals(
            latest_time_interval=TimeInterval(
                start_dt=parse("2020-11-18 00:00:00.00"),
                end_dt=parse("2020-11-19 00:00:00.00"),
            ),
            num_lookback_intervals=7,
            lookback_timedelta=timedelta(days=1),
            reverse=False,
        )
    ]
    evaluate_time_intervals = [
        time_interval.to_str_tuple()
        for time_interval in generate_lookback_intervals(
            latest_time_interval=TimeInterval(
                start_dt=parse("2020-11-19 00:00:00.00"),
                end_dt=parse("2020-11-20 00:00:00.00"),
            ),
            num_lookback_intervals=7,
            lookback_timedelta=timedelta(days=1),
            reverse=False,
        )
    ]
    start_dt = "2020-11-18 20:00:00.00"
    end_dt = "2020-11-20 00:00:00.00"
    time_delta = 30
    initial_product_funds = "0.000000"
    initial_quote_funds = "10000.00"
    num_warmup_time_steps = 480
    result_metric = InfoDictKey.Name(InfoDictKey.portfolioValue)
    reward_strategy = RewardStrategy.Name(RewardStrategy.LogReturnRewardStrategy)

    num_samples = 100
    optimization_mode = OptimizationMode.MAX
    search_algorithm = SearchAlgorithms.HYPER_OPT_SEARCH
    search_algorithm_config = {"metric": result_metric, "mode": optimization_mode}
    tune_config = {
        "fastWindowSize": "tune.uniform(60, 480)",
        "slowWindowSize": "tune.uniform(60, 480)",
    }
    resources_per_trial = {"cpu": 2.0}
