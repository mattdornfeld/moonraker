"""
 [summary]
"""
import numpy as np


def assert_arrays_not_equal(x: np.ndarray, y: np.ndarray) -> None:
    """
    assert_arrays_not_equal [summary]

    Args:
        x (np.ndarray): [description]
        y (np.ndarray): [description]

    Raises:
        AssertionError
    """
    assert np.any(np.not_equal(x, y))
