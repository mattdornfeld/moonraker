"""Constants for supervised modules
"""

from typing import List


class Columns:
    """Columns of Pandas DataFrames used in the module
    """

    LABEL = "label"
    UPPER_BARRIER = "upper_barrier"
    LOWER_BARRIER = "lower_barrier"


class PortfolioParameters:
    """Parameters passed into for a portfolio before evaluating
    """

    BARRIER_TIME_STEPS = "barrier_time_steps"
    FEE_FRACTION = "fee_fraction"
    INITIAL_QUOTE_FUNDS = "initial_quote_funds"
    RESULT_METRIC = "result_metric"


class Labels:
    """Labels assigned by the triple barrier method
    """

    NEGATIVE = -1.0
    NEUTRAL = 0.0
    POSITIVE = 1.0

    @classmethod
    def as_list(cls) -> List[float]:
        return [cls.NEGATIVE, cls.NEUTRAL, cls.POSITIVE]


class ResultMetrics:
    """Metrics against which a portfolio can be optimized
    """

    SHARPE_RATIO = "sharpe_ratio"
