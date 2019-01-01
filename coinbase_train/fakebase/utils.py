"""Summary
"""
from math import pi, sin
from queue import Queue

import numpy as np

from bintrees import BinaryTree as BinaryTreeBase

def get_precision(float_num):
    """Approximate number of places to the right of the decimal. 
    This result may be off to rounding errors associated with the
    storage of floating point numbers.
    
    Args:
        float_num (float): Description
    
    Returns:
        int: Description
    """
    return len(str(float_num).split('.')[1])

def one_hot_encode_order_side(order_side):
    """Summary
    
    Args:
        order_side (str): Description
    
    Returns:
        List[int]: [0, 1] if order_side=='buy'
            [1, 0] if order_side=='sell'
    """
    return [0, 1] if order_side == 'buy' else [1, 0]

def one_hot_encode_order_type(order_type):
    """Summary
    
    Args:
        order_type (str): Description
    
    Returns:
        List[int]: [0, 1] if order_type=='limit'
            [1, 0] if order_type=='market'
    """
    return [0, 1] if order_type == 'limit' else [1, 0]

def opposite_side(order_side):
    """Summary
    
    Args:
        order_side (str): Description
    
    Returns:
        str: Description
    """
    return 'sell' if order_side == 'buy' else 'buy'

def populate_datetime_queue(start_dt, end_dt, time_delta, dt_queue=None):
    """Summary
    
    Args:
        start_dt (datetime.datetime): Description
        end_dt (datetime.datetime): Description
        time_delta (datetime.timedelta): Description
        dt_queue (Queue, optional): Description
    
    Returns:
        TYPE: Description
    """
    if dt_queue is None:
        dt_queue = Queue()

    _datetime = start_dt

    while _datetime < end_dt:

        dt_queue.put(_datetime)

        _datetime += time_delta

    return dt_queue

def preprocess_time(time):
    """Accepts a datetime object and returns a list
    of the form [day_of_week, hour_of_day, minute_of_hour].
    The coordinates of this are represented in polar coordinates.return

    
    Args:
        time (datetime.datetime): Description
    
    Returns:
        List[float]: Description
    """
    day_of_week = sin(2 * pi * time.weekday() / 7)
    hour_of_day = sin(2 * pi * time.hour / 24)
    minute_of_hour = sin(2 * pi * time.minute / 60)

    return [day_of_week, hour_of_day, minute_of_hour]

def vstack(list_of_arrays, width):
    """If len(list_of_arrays) > 0 calls np.vstack on list. 
    If len(list_of_arrays) == 0 returns an array of zeros
    with dimensions (1, width).
    
    Args:
        list_of_arrays (List[np.ndarray]): Description
        width (int): Description
    
    Returns:
        np.ndarray: Description
    """
    return np.zeros((1, width)) if len(list_of_arrays) == 0 else np.vstack(list_of_arrays)

class BinaryTree(BinaryTreeBase):

    """Summary
    """
    
    def max_key_or_default(self, default_value=None):
        """Summary
        
        Args:
            default_value (None, optional): Description
        
        Returns:
            TYPE: Description
        """
        try:
            return super().max_key()
        except ValueError:
            return default_value

    def min_key_or_default(self, default_value=None):
        """Summary
        
        Args:
            default_value (None, optional): Description
        
        Returns:
            TYPE: Description
        """
        try:
            return super().min_key()
        except ValueError:
            return default_value

class ExchangeFinishedException(Exception):

    """Summary
    """
    
    def __init__(self, msg=None):
        """Summary
        
        Args:
            msg (str, optional): Description
        """
        if msg is None:
            msg = 'The Exchange has reached the end of the training period.'

        super().__init__(msg)

class IllegalTransactionException(Exception):
    """Summary
    """

class UniversalSet:
    """A convenience class used for simplifying code. Everything is
    a member of this set. This is useful when a function filters on
    a variable and a value of None is used to signify no filtering.
    """
    
    def __contains__(self, value):
        """Summary
        
        Args:
            value (TYPE): Description
        
        Returns:
            TYPE: Description
        """
        return True
        