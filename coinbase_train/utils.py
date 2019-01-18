"""Summary

Attributes:
    EnvironmentConfigs (namedtuple): Description
    HyperParameters (namedtuple): Description
"""
from collections import namedtuple
import os
from pathlib import Path

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
                                 'actor_account_funds_attention_dim', 
                                 'actor_account_funds_hidden_dim', 
                                 'actor_account_orders_hidden_dim', 
                                 'actor_account_orders_attention_dim', 
                                 'actor_matches_attention_dim', 
                                 'actor_matches_hidden_dim', 
                                 'actor_merged_branch_attention_dim', 
                                 'actor_merged_branch_hidden_dim', 
                                 'actor_order_book_num_filters', 
                                 'actor_order_book_kernel_size', 
                                 'actor_orders_attention_dim', 
                                 'actor_orders_hidden_dim',
                                 'batch_size',
                                 'critic_account_funds_attention_dim', 
                                 'critic_account_funds_hidden_dim', 
                                 'critic_account_orders_hidden_dim', 
                                 'critic_account_orders_attention_dim', 
                                 'critic_matches_attention_dim', 
                                 'critic_matches_hidden_dim', 
                                 'critic_merged_branch_attention_dim', 
                                 'critic_merged_branch_hidden_dim', 
                                 'critic_order_book_num_filters', 
                                 'critic_order_book_kernel_size', 
                                 'critic_orders_attention_dim', 
                                 'critic_orders_hidden_dim',
                                 'critic_output_branch_hidden_dim',
                                 'num_time_steps'])
