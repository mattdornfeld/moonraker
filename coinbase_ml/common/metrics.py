"""
 [summary]
"""
from dataclasses import asdict, dataclass
from enum import Enum
from statistics import mean
from typing import Dict, List

from coinbase_ml.common.featurizers import Featurizer
from coinbase_ml.common.utils import stdev


class MetricNames(Enum):
    """
    MetricNames [summary]
    """

    PORTFOLIO_VALUE = "portolio_value"
    REWARD = "reward"
    ROI = "roi"


@dataclass
class Aggregates:
    """
     [summary]
    """

    open: float
    close: float
    max: float
    mean: float
    min: float
    stdev: float


MetricsDict = Dict[MetricNames, float]


class MetricsRecorder:
    """
     [summary]
    """

    def __init__(self, featurizer: Featurizer) -> None:
        """
        __init__ [summary]

        Args:
            featurizer (Featurizer): [description]
        """
        self.featurizer = featurizer
        self._initial_portfolio_value = self.featurizer.calculate_portfolio_value()
        self._metrics: List[MetricsDict] = []

    def _check_is_empty(self) -> None:
        """
        _check_is_empty [summary]

        Raises:
            ValueError: [description]
        """
        if not self._metrics:
            raise ValueError(
                "No metrics to aggregate. Call MetricsRecorder.update_metrics."
            )

    def calc_aggregates(self) -> Dict[MetricNames, Aggregates]:
        """
        calc_aggregates [summary]

        Returns:
            Dict[MetricNames, Aggregates]: [description]
        """
        self._check_is_empty()

        aggregates: Dict[MetricNames, Aggregates] = {}
        for metric_name in MetricNames:

            metric_list = [m[metric_name] for m in self._metrics]

            aggregates[metric_name] = Aggregates(
                open=metric_list[0],
                close=metric_list[-1],
                max=max(metric_list),
                mean=mean(metric_list),
                min=min(metric_list),
                stdev=stdev(metric_list),
            )

        return aggregates

    def calc_roi(self) -> float:
        """
        calc_roi [summary]

        Returns:
            float: [description]
        """
        portfolio_value = self.featurizer.calculate_portfolio_value()
        return (
            portfolio_value - self._initial_portfolio_value
        ) / self._initial_portfolio_value

    def get_latest(self) -> MetricsDict:
        """
        get_latest [summary]

        Returns:
            MetricsDict: [description]
        """
        self._check_is_empty()
        return self._metrics[-1]

    def reset(self) -> None:
        """
        reset [summary]
        """
        self._metrics = []

    def update_metrics(self) -> None:
        """
        update_metrics [summary]
        """
        self._metrics.append(
            {
                MetricNames.PORTFOLIO_VALUE: self.featurizer.calculate_portfolio_value(),
                MetricNames.REWARD: self.featurizer.calculate_reward(),
                MetricNames.ROI: self.calc_roi(),
            }
        )


def convert_to_sacred_log_format(
    aggregates: Dict[MetricNames, Aggregates]
) -> Dict[str, float]:
    """
    convert_to_sacred_log_format [summary]

    Args:
        aggregates (Dict[MetricNames, Aggregates]): [description]

    Returns:
        Dict[str, float]: [description]
    """
    converted_aggregates: Dict[str, float] = {}
    for metric_name, aggregate in aggregates.items():
        converted_aggregate: Dict[str, float] = asdict(aggregate)
        for metric_name_suffix, value in converted_aggregate.items():
            converted_aggregates[f"{metric_name.value}_{metric_name_suffix}"] = value

    return converted_aggregates
