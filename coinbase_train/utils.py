"""Summary

Attributes:
    Number (typing.TypeVar): Description
"""
from datetime import datetime, timedelta
from decimal import Decimal
from math import sqrt
import os
from pathlib import Path
from statistics import stdev as base_stdev
from typing import Any, Callable, List, NamedTuple, Union

import numpy as np
from sacred.stflow import LogFileWriter
import tensorflow as tf

from coinbase_train import constants as c

Number = Union[Decimal, float, int]

def add_tensorboard_dir_to_sacred(sacred_experiment, tensorboard_dir):
    """Summary
    
    Args:
        sacred_experiment (sacred.Experiment): Description
        tensorboard_dir (Path): Description
    """
    with LogFileWriter(sacred_experiment):
        tf.summary.FileWriter(logdir=str(tensorboard_dir))

def calc_nb_max_episode_steps(end_dt, num_time_steps, start_dt, time_delta):
    """Summary
    
    Args:
        end_dt (datetime.datetime): Description
        num_time_steps (int): Description
        start_dt (datetime.datetime): Description
        time_delta (datetime.timedelta): Description
    
    Returns:
        int: Description
    """
    return int((end_dt - start_dt) / time_delta) - num_time_steps - 1

def clamp_to_range(num, smallest, largest): 
    """Returns num clamped to interval [smallest, largest]
    
    Args:
        num (Sortable): Description
        smallest (Sortable): Description
        largest (Sortable): Description
    
    Returns:
        Sortable: Description
    """
    return max(smallest, min(num, largest))

def convert_to_bool(num):
    """Summary
    
    Args:
        num (Union[float, int]): Description
    
    Returns:
        TYPE: Description
    """
    return bool(round(clamp_to_range(num, 0, 1)))

def make_tensorboard_dir(_run):
    """Summary
    
    Args:
        _run (sacred.run.Run): Description
    
    Returns:
        Path: Description
    """
    ex_name = _run.experiment_info['name']
    ex_id = _run._id #pylint: disable=W0212
    tensorboard_dir = Path(c.TENSORBOARD_ROOT_DIR) / f'{ex_name}_{ex_id}'
    os.mkdir(tensorboard_dir)

    return tensorboard_dir

def make_model_dir(_run):
    """Summary
    
    Args:
        _run (sacred.run.Run): Description
    
    Returns:
        Path: Description
    """
    ex_name = _run.experiment_info['name']
    ex_id = _run._id #pylint: disable=W0212
    model_dir = Path(c.SAVED_MODELS_ROOT_DIR) / f'{ex_name}_{ex_id}'
    os.mkdir(model_dir)

    return model_dir

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

    """Summary
    """
    
    attention_dim: int
    batch_size: int
    depth: int
    learning_rate: float
    num_filters: int
    num_stacks: int
    num_time_steps: int

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

    def __repr__(self):
        """Summary
        
        Returns:
            str: Description
        """
        return f'<Normalized {self.name}>'

    def _update_mean(self, result: float):
        """Summary
        
        Args:
            result (float): Description
        """
        self._mean = (result + self._num_samples * self._mean) / (self._num_samples + 1)

    def _update_variance(self, result: float):
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
