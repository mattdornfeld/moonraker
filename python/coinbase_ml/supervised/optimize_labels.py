"""Entrypoint for labeling module. Generates optimial triple barrier labels using ray.tune.
"""
from math import ceil
from datetime import timedelta
from pprint import pprint
from typing import Dict, Any

import pandas as pd
import vectorbt as vbt
from funcy import func_partial
from ray import tune

from coinbase_ml.common.utils.ray_utils import get_search_algorithm, eval_tune_config
from coinbase_ml.supervised.constants import PortfolioParameters
from coinbase_ml.supervised.experiment_configs import SACRED_EXPERIMENT
from coinbase_ml.supervised.generate_data import (
    generate_prices_for_timestamps_starting_now,
)
from coinbase_ml.supervised.portfolio import (
    create_portfolio_with_triple_barrier_positions,
)


def _create_portfolio_from_config(
    prices: pd.Series, config: Dict[str, Any]
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
    prices: pd.Series,
) -> None:
    """Create and evaluate a vbt.Portfolio. Report its results to ray.tune.
    """
    portfolio = _create_portfolio_from_config(prices, config)
    stats = _process_portfolio_stats(portfolio)
    result_metric = config[PortfolioParameters.RESULT_METRIC]
    tune.report(**{result_metric: stats[result_metric]})


def _get_price_data() -> pd.Series:
    return generate_prices_for_timestamps_starting_now(
        mu=0.0001,
        sigma=0.01,
        start_price=5.0,
        num_timesteps=1000,
        time_delta=timedelta(seconds=60),
    )


@SACRED_EXPERIMENT.automain
def generate_optimal_labels(
    initial_quote_funds: float,
    fee_fraction: float,
    num_samples: int,
    optimization_mode: str,
    resources_per_trial: Dict[str, float],
    result_metric: str,
    search_algorithm: str,
    search_algorithm_config: dict,
    tune_config: Dict[str, str],
    visualize_portfolio: bool,
) -> None:
    """Generates triple barrier labels, optimized by ray.tune, for a given experiment config.
    """
    prices = _get_price_data()

    experiment_analysis = tune.run(
        run_or_experiment=func_partial(create_and_evaluate_portfolio, prices=prices),
        config={
            PortfolioParameters.INITIAL_QUOTE_FUNDS: initial_quote_funds,
            PortfolioParameters.FEE_FRACTION: fee_fraction,
            PortfolioParameters.RESULT_METRIC: result_metric,
            **eval_tune_config(tune_config),
        },
        max_failures=3,
        metric=result_metric,
        mode=optimization_mode,
        num_samples=num_samples,
        resources_per_trial=resources_per_trial,
        search_alg=get_search_algorithm(search_algorithm, search_algorithm_config),
        reuse_actors=True,
    )

    best_search_config = experiment_analysis.best_config

    portfolio = _create_portfolio_from_config(prices, best_search_config)
    portfolio_stats = _process_portfolio_stats(portfolio)

    print("Best Tune Config: ")
    pprint(best_search_config)
    print("\nBest Portfolio Stats: ")
    pprint(portfolio_stats)

    if visualize_portfolio:
        portfolio.plot().show()

    return portfolio_stats[result_metric]
