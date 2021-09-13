"""Generates optimal triple barrier labels using ray.tune.
"""
from math import ceil
from pprint import pprint
from typing import Dict, Any, Tuple

import pandas as pd
import vectorbt as vbt
from funcy import func_partial
from ray import tune

from coinbase_ml.common.utils.ray_utils import get_search_algorithm, eval_tune_config
from coinbase_ml.supervised.constants import PortfolioParameters, Columns
from coinbase_ml.supervised.experiment_configs import SACRED_EXPERIMENT
from coinbase_ml.supervised.label import calc_triple_barrier_labels_and_barriers
from coinbase_ml.supervised.portfolio import (
    create_portfolio_with_triple_barrier_positions,
)
from coinbase_ml.supervised.types import Prices


def _create_portfolio_from_config(
    prices: Prices, config: Dict[str, Any]
) -> vbt.Portfolio:
    return create_portfolio_with_triple_barrier_positions(
        prices,
        ceil(config[PortfolioParameters.BARRIER_TIME_STEPS]),
        float(config[PortfolioParameters.INITIAL_QUOTE_FUNDS]),
        float(config[PortfolioParameters.FEE_FRACTION]),
    )


def _process_portfolio_stats(portfolio: vbt.Portfolio) -> Dict[str, Any]:
    return {
        k.lower().replace(" ", "_"): v for k, v in portfolio.stats().to_dict().items()
    }


def create_and_evaluate_portfolio(
    config: Dict[str, Any],
    checkpoint_dir: str,  # pylint: disable=unused-argument,
    prices: Prices,
) -> None:
    """Create and evaluate a vbt.Portfolio. Report its results to ray.tune.
    """
    portfolio = _create_portfolio_from_config(prices, config)
    stats = _process_portfolio_stats(portfolio)
    performance_metric = config[PortfolioParameters.PERFORMANCE_METRIC]
    tune.report(**{performance_metric: stats[performance_metric]})


@SACRED_EXPERIMENT.capture
def generate_optimal_labels(
    prices: Prices,
    initial_quote_funds: float,
    fee_fraction: float,
    num_samples: int,
    optimization_mode: str,
    resources_per_trial: Dict[str, float],
    performance_metric: str,
    search_algorithm: str,
    search_algorithm_config: dict,
    tune_config: Dict[str, str],
    visualize_portfolio: bool,
) -> Tuple[pd.Series, Dict[str, Any]]:
    """Generates triple barrier labels, optimized by ray.tune, for a given experiment config.
    """
    experiment_analysis = tune.run(
        run_or_experiment=func_partial(create_and_evaluate_portfolio, prices=prices),
        config={
            PortfolioParameters.INITIAL_QUOTE_FUNDS: initial_quote_funds,
            PortfolioParameters.FEE_FRACTION: fee_fraction,
            PortfolioParameters.PERFORMANCE_METRIC: performance_metric,
            **eval_tune_config(tune_config),
        },
        max_failures=3,
        metric=performance_metric,
        mode=optimization_mode,
        num_samples=num_samples,
        resources_per_trial=resources_per_trial,
        search_alg=get_search_algorithm(search_algorithm, search_algorithm_config),
        reuse_actors=True,
    )

    best_search_config = experiment_analysis.best_config
    best_labels = calc_triple_barrier_labels_and_barriers(
        prices, ceil(best_search_config[PortfolioParameters.BARRIER_TIME_STEPS])
    )[Columns.LABEL]
    best_portfolio = _create_portfolio_from_config(prices, best_search_config)
    best_portfolio_stats = _process_portfolio_stats(best_portfolio)

    print("Best Tune Config: ")
    pprint(best_search_config)
    print("\nBest Portfolio Stats: ")
    pprint(best_portfolio_stats)

    if visualize_portfolio:
        best_portfolio.plot().show()

    return best_labels, best_portfolio_stats[performance_metric]
