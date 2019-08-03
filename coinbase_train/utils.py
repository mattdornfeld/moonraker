"""Summary

Attributes:
    Number (typing.TypeVar): Description
"""
from datetime import datetime, timedelta
from decimal import Decimal
from math import sqrt
from pathlib import Path
from statistics import stdev as base_stdev
from typing import Any, Callable, List, NamedTuple, Union

import numpy as np
import tensorflow as tf
from sacred import Experiment
from sacred.stflow import LogFileWriter
from sacred.run import Run

from coinbase_train import constants as c

Number = Union[Decimal, float, int]

def add_tensorboard_dir_to_sacred(sacred_experiment: Experiment,
                                  tensorboard_dir: Path) -> None:
    """
    add_tensorboard_dir_to_sacred [summary]

    Args:
        sacred_experiment (Experiment): [description]
        tensorboard_dir (Path): [description]

    Returns:
        None: [description]
    """
    with LogFileWriter(sacred_experiment):
        tf.summary.FileWriter(logdir=str(tensorboard_dir))

def calc_nb_max_episode_steps(end_dt: datetime,
                              num_time_steps: int,
                              start_dt: datetime,
                              time_delta: timedelta) -> int:
    """
    calc_nb_max_episode_steps [summary]

    Args:
        end_dt (datetime): [description]
        num_time_steps (int): [description]
        start_dt (datetime): [description]
        time_delta (timedelta): [description]

    Returns:
        int: [description]
    """
    return int((end_dt - start_dt) / time_delta) - num_time_steps - 1

def clamp_to_range(num: float, smallest: float, largest: float) -> float:
    """
    clamp_to_range [summary]

    Args:
        num (float): [description]
        smallest (float): [description]
        largest (float): [description]

    Returns:
        float: [description]
    """
    return max(smallest, min(num, largest))

def convert_to_bool(num: bool) -> bool:
    """
    convert_to_bool [summary]

    Args:
        num (bool): [description]

    Returns:
        bool: [description]
    """
    return bool(round(clamp_to_range(num, 0, 1)))

def get_tensorboard_path(_run: Run) -> Path:
    """
    get_tensorboard_path [summary]

    Args:
        _run (Run): [description]

    Returns:
        Path: [description]
    """
    ex_name = _run.experiment_info['name']
    ex_id = _run._id #pylint: disable=W0212
    tensorboard_path = Path(c.TENSORBOARD_ROOT_DIR) / f'{ex_name}_{ex_id}'

    return tensorboard_path

def get_model_path(_run: Run) -> Path:
    """
    get_model_path [summary]

    Args:
        _run (Run): [description]

    Returns:
        Path: [description]
    """
    ex_name = _run.experiment_info['name']
    ex_id = _run._id #pylint: disable=W0212
    model_path = Path(c.SAVED_MODELS_ROOT_DIR) / f'{ex_name}_{ex_id}'

    return model_path

def min_max_normalization(max_value: float, min_value: float, num: float) -> float:
    """Summary

    Args:
        max_value (float): Description
        min_value (float): Description
        num (float): Description

    Returns:
        float: Description
    """
    return (num - min_value) / (max_value - min_value)

def pad_to_length(array: np.ndarray,
                  length: int,
                  pad_value: float = 0.0) -> np.ndarray:
    """Summary

    Args:
        array (np.ndarray): Description
        length (int): Description
        pad_value (float, optional): Description

    Returns:
        np.ndarray: Description
    """
    return np.pad(array=array,
                  pad_width=((0, length), (0, 0)),
                  mode='constant',
                  constant_values=(pad_value,))


def stdev(data: List[Number]) -> Number:
    """Basically statistics.stdev but does not throw an
    error for list of length 1. For lists of length 1 will
    return 0. Otherwise returns statistics.stdev of list.

    Args:
        data (List[Number]): Description

    Returns:
        Number: stdev
    """
    _data = 2 * data if len(data) == 1 else data

    return base_stdev(_data)

class EnvironmentConfigs(NamedTuple):
    """Summary
    """
    end_dt: datetime
    initial_usd: Decimal
    initial_btc: Decimal
    num_episodes: int
    num_warmup_time_steps: int
    start_dt: datetime
    time_delta: timedelta

class EnvironmentFinishedException(Exception):
    """Summary
    """
    def __init__(self, msg=None):
        """Summary

        Args:
            msg (str, optional): Description
        """
        if msg is None:
            msg = (
                'This environment has finished the training episode. '
                'Call self.reset to start a new one.'
                )


        super().__init__(msg)

class HyperParameters(NamedTuple):
    """
    HyperParameters [summary]

    Args:
        NamedTuple ([type]): [description]
    """
    account_funds_num_units: int
    account_funds_tower_depth: int
    batch_size: int
    deep_lob_tower_attention_dim: int
    deep_lob_tower_conv_block_num_filters: int
    deep_lob_tower_leaky_relu_slope: float
    discount_factor: float
    learning_rate: float
    num_time_steps: int
    output_tower_depth: int
    output_tower_num_units: int
    time_series_tower_attention_dim: int
    time_series_tower_depth: int
    time_series_tower_num_filters: int
    time_series_tower_num_stacks: int

class NormalizedOperation:

    """A class for the normalizing the output of any operator that outputs a float.
    Normalization is done using the z-normalization based on a running mean and variance.and
    Useful for reinforcement learning algorithms.
    """

    def __init__(self, operator: Callable[[Any], float], name: str, normalize: bool = True):
        """Summary

        Args:
            operator (Callable[[Any], float]): This operator will be executed when
            name (str): Description
            normalize (bool, optional): The result of __call__ is Normalized
            __call__ is called. If normalize is True the output will be noramlized.
            using running mean and variance if True. If False __call__ will
            simply apply operator.
        """
        self._mean = 0.0
        self._m = 0.0
        self._num_samples = 0
        self._operator: Callable[[Any], float] = operator
        self._s = 0.0
        self._variance = 0.0
        self.name = name
        self.normalize = normalize

    def __call__(self, operand: Any) -> float:
        """Summary

        Args:
            operand (Any): Description

        Returns:
            float: Description
        """
        _result = self._operator(operand)

        if self.normalize:
            self._num_samples += 1
            self._update_mean(_result)
            self._update_variance(_result)

            result = (_result - self._mean) / (sqrt(self._variance) + 1e-12)
        else:
            result = _result

        return result

    def __repr__(self) -> str:
        """Summary

        Returns:
            str: Description
        """
        return f'<Normalized {self.name}>'

    def _update_mean(self, result: float) -> None:
        """Summary

        Args:
            result (float): Description
        """
        self._mean = (result + self._num_samples * self._mean) / (self._num_samples + 1)

    def _update_variance(self, result: float) -> None:
        """Summary

        Args:
            result (float): Description
        """
        if self._num_samples == 1:
            self._m = result
            self._variance = 0
        else:
            old_m = self._m
            self._m = old_m + (result - old_m) / self._num_samples

            old_s = self._s
            self._s = old_s + (result - old_m) * (result - self._m)

            self._variance = self._s / (self._num_samples - 1)
