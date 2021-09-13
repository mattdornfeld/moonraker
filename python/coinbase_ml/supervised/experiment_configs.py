"""Experiment configs for labeling a price time series with the tripple barrier method
"""
from coinbase_ml.common.constants.tune import SearchAlgorithms, OptimizationMode
from coinbase_ml.common.utils.sacred_utils import create_sacred_experiment
from coinbase_ml.supervised.constants import PerformanceMetrics, PortfolioParameters
from featurizers_pb2 import Featurizer

SACRED_EXPERIMENT = create_sacred_experiment("generate-feature-and-labels")

# pylint: disable=unused-variable


@SACRED_EXPERIMENT.named_config
def generate_labels_dev():
    """Dev configs
    """
    start_dt = "2020-11-19 10:00:00.00"
    end_dt = "2020-11-19 12:00:00.00"
    time_delta = 30
    initial_product_funds = float("0.00")
    initial_quote_funds = float("100.00")
    # product_id = cc.PRODUCT_ID

    featurizer = Featurizer.Name(Featurizer.OrderBook)
    featurizer_configs = {"featureBufferSize": 10, "orderBookDepth": 10}
    num_warmup_time_steps = 10

    fee_fraction = 0.005
    num_samples = 100
    performance_metric = PerformanceMetrics.SHARPE_RATIO
    optimization_mode = OptimizationMode.MAX
    search_algorithm = SearchAlgorithms.HYPER_OPT_SEARCH
    visualize_portfolio = True

    search_algorithm_config = {
        "n_initial_points": 10,
        "metric": performance_metric,
        "mode": optimization_mode,
    }

    tune_config = {
        PortfolioParameters.BARRIER_TIME_STEPS: "tune.uniform(1.0, 200.0)",
    }

    resources_per_trial = {"cpu": 1.0}
