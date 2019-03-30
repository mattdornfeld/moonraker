"""Summary

Attributes:
    EnvironmentConfigs (namedtuple): Description
    HyperParameters (namedtuple): Description
"""
from collections import namedtuple
import os
from pathlib import Path

import numpy as np
from sacred.stflow import LogFileWriter
import tensorflow as tf

from coinbase_train import constants as c

def add_tensorboard_dir_to_sacred(sacred_experiment, tensorboard_dir):
    """Summary
    
    Args:
        sacred_experiment (sacred.Experiment): Description
        tensorboard_dir (Path): Description
    """
    with LogFileWriter(sacred_experiment):
        tf.summary.FileWriter(logdir=str(tensorboard_dir))

def calc_nb_max_episode_steps(end_dt, start_dt, time_delta):
    """Summary
    
    Args:
        end_dt (datetime.datetime): Description
        start_dt (datetime.datetime): Description
        time_delta (datetime.timedelta): Description
    
    Returns:
        int: Description
    """
    return int((end_dt - start_dt) / time_delta) - c.NUM_TIME_STEPS - 1

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

def round_to_min_precision(num, min_precision):
    """Rounds num to min_precision if abs(num) <  10**-min_precision.
    Returns num otherwise.
    
    Args:
        num (float): Description
        min_precision (int): Description
    
    Returns:
        float: Description
    """
    sign = np.sign(num + 1e-12)

    return sign * 10**-min_precision if abs(num) < 10**-min_precision else num

EnvironmentConfigs = namedtuple(typename='EnvironmentConfigs', 
                                field_names=['end_dt', 'initial_usd', 'initial_btc', 
                                             'num_episodes', 'start_dt', 'time_delta'])

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

HyperParameters = namedtuple(typename='HyperParameters',
                             field_names=[
                                 'attention_dim',
                                 'batch_size',
                                 'depth',
                                 'learning_rate',
                                 'num_filters',
                                 'num_stacks',
                                 'num_time_steps'])
