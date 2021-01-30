# pylint: disable=unused-variable

from coinbase_ml.common.protos.environment_pb2 import (
    InfoDictKey,
    RewardStrategy,
    Featurizer,
)
from coinbase_ml.trend_following.experiment_configs.constants import SACRED_EXPERIMENT


@SACRED_EXPERIMENT.named_config
def ema_crossover_dev():
    actionizer_name = "coinbase_ml.common.actionizers.EmaCrossover"
    featurizer = Featurizer.Name(Featurizer.NoOp)
    start_dt = "2020-11-19 00:00:00.00"
    end_dt = "2020-11-19 01:00:00.00"
    time_delta = 30
    initial_product_funds = "0.000000"
    initial_quote_funds = "10000.00"
    num_warmup_time_steps = 3
    result_metric = InfoDictKey.Name(InfoDictKey.portfolioValue)
    reward_strategy = RewardStrategy.Name(RewardStrategy.LogReturnRewardStrategy)

    num_samples = 10
    optimization_mode = "max"
    search_algorithm = "ray.tune.suggest.hyperopt.HyperOptSearch"
    search_algorithm_config = {"metric": result_metric, "mode": optimization_mode}
    tune_config = {
        "fastWindowSize": "tune.uniform(60, 480)",
        "slowWindowSize": "tune.uniform(60, 480)",
    }
    resources_per_trial = {"cpu": 4.0}
