"""Constants for use with ray.tune
"""


class OptimizationMode:
    """Possible optimization modes
    """

    MAX = "max"
    MIN = "min"


class SearchAlgorithms:
    """Possible Python paths to search algorithms
    """

    HYPER_OPT_SEARCH = "ray.tune.suggest.hyperopt.HyperOptSearch"
