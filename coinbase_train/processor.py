"""Summary
"""
from typing import Callable, List, Tuple

from funcy import partial
import numpy as np
from rl.core import Processor


class CoibaseEnvironmentProcessor(Processor):

    """This class takes output state from Environment.step
    and converts it to a form that can be processed by the
    actor and critic models.
    """
    
    @staticmethod
    def _find_largest_size(list_of_arrays: List[np.ndarray], axis: int) -> int:
        """Summary
        
        Args:
            list_of_arrays (List[np.ndarray]): Description
            axis (int): Description
        
        Returns:
            int: Description
        """
        largest_size = 1
        for array in list_of_arrays:
            new_size = array.shape[axis]
            largest_size = new_size if new_size > largest_size else largest_size

        return largest_size

    @staticmethod
    def _pad_list_of_arrays_to_same_size(
            list_of_arrays: List[np.ndarray], 
            pad_width_fn: Callable[[np.ndarray], Tuple[int]]) -> List[np.ndarray]:
        """Summary
        
        Args:
            list_of_arrays (List[np.ndarray]): Description
            pad_width_fn (Callable[[np.ndarray], Tuple[int]]): Description
        
        Returns:
            List[np.ndarray]: Description
        """
        return [np.pad(array=array, 
                       pad_width=pad_width_fn(array), 
                       mode='constant', 
                       constant_values=0)
                for array in list_of_arrays]

    
    def _pad_and_batch_list_of_arrays(
            self,
            list_of_arrays: List[np.ndarray], 
            pad_axis: int,
            pad_width_fn: Callable[[np.ndarray, int, int], Tuple[int]]) -> np.ndarray:
        """Summary
        
        Args:
            list_of_arrays (List[np.ndarray]): Description
            pad_axis (int): Description
            pad_width_fn (Callable[[np.ndarray, int, int], Tuple[int]]): Description
        
        Returns:
            np.ndarray: Description
        """
        pad_to_length = self._find_largest_size(list_of_arrays, axis=pad_axis)
        
        padded_arrays = self._pad_list_of_arrays_to_same_size(
            list_of_arrays=list_of_arrays,
            pad_width_fn=partial(pad_width_fn, pad_axis=pad_axis, pad_to_length=pad_to_length)) 
        
        return np.vstack([np.expand_dims(array, axis=0) for array in padded_arrays])

    def process_state_batch(self, batch: List[List[np.ndarray]]) -> List[np.ndarray]:
        """Summary
        
        Args:
            batch (List[List[np.ndarray]]): Description
        
        Returns:
            List[np.ndarray]: Description
        """

        batched_account_funds = np.vstack([np.expand_dims(array, axis=0) for 
                                           array in [a[0][0] for a in batch]])

        batched_order_books = self._pad_and_batch_list_of_arrays(
            list_of_arrays=[a[0][1] for a in batch],
            pad_axis=1,
            pad_width_fn=lambda array, pad_axis, pad_to_length: ((0, pad_to_length - array.shape[pad_axis]), (0, 0), (0, 0))  #pylint: disable=C0301
            )

        batched_time_series = np.vstack([np.expand_dims(array, axis=0) for 
                                         array in [a[0][2] for a in batch]])

        return [batched_account_funds, 
                batched_order_books, 
                batched_time_series]
