"""Experiment configs for labeling a price time series with the tripple barrier method
"""

from coinbase_ml.common.constants.tune import SearchAlgorithms, OptimizationMode
from coinbase_ml.common.utils.sacred_utils import create_sacred_experiment
from coinbase_ml.supervised.constants import ResultMetrics, PortfolioParameters

SACRED_EXPERIMENT = create_sacred_experiment("generate-labels")

# pylint: disable=unused-variable


@SACRED_EXPERIMENT.named_config
def generate_labels_dev():
    """Dev configs
    """
    initial_quote_funds = float("100.00")
    fee_fraction = 0.005
    num_samples = 100
    result_metric = ResultMetrics.SHARPE_RATIO
    optimization_mode = OptimizationMode.MAX
    search_algorithm = SearchAlgorithms.HYPER_OPT_SEARCH
    visualize_portfolio = True

    search_algorithm_config = {
        "n_initial_points": 10,
        "metric": result_metric,
        "mode": optimization_mode,
    }

    tune_config = {
        PortfolioParameters.BARRIER_TIME_STEPS: "tune.uniform(1.0, 120.0)",
    }

    resources_per_trial = {"cpu": 1.0}
