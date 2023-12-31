"""
time_utils is a submodule for generating lookback time intervals
for the purpose of creating Fakebase simulation environments on
different days
"""
from __future__ import annotations
from datetime import datetime, timedelta
from random import random
from typing import Any, List, Tuple

from dateutil.parser import parse


class TimeInterval:
    """
    TimeInterval is a class that encapsulates the properties of two
    datetime objects
    """

    def __init__(self, end_dt: datetime, start_dt: datetime):
        """
        __init__ [summary]

        Args:
            end_dt (datetime): [description]
            start_dt (datetime): [description]
        """
        self.end_dt = end_dt
        self.start_dt = start_dt

    def __add__(self, time_delta: timedelta) -> TimeInterval:
        """
        __add__ [summary]

        Args:
            time_delta (timedelta): [description]

        Returns:
            TimeInterval: [description]
        """
        return self.__class__(
            end_dt=self.end_dt + time_delta, start_dt=self.start_dt + time_delta
        )

    def __eq__(self, other: Any) -> bool:
        """
        __eq__ [summary]

        Args:
            other (Any): [description]

        Raises:
            TypeError: [description]

        Returns:
            bool: [description]
        """
        if isinstance(other, TimeInterval):
            return_val = (self.end_dt == other.end_dt) and (
                self.start_dt == other.start_dt
            )
        else:
            raise TypeError

        return return_val

    def __hash__(self) -> int:
        """
        __hash__ [summary]

        Returns:
            int: [description]
        """
        return (self.end_dt, self.start_dt).__hash__()

    def __len__(self) -> timedelta:
        """
        __len__ [summary]

        Returns:
            timedelta: [description]
        """
        return self.end_dt - self.start_dt

    def __repr__(self) -> str:
        """
        __repr__ [summary]

        Returns:
            str: [description]
        """
        return f"<{self.start_dt} - {self.end_dt}>"

    def __sub__(self, time_delta: timedelta) -> TimeInterval:
        """
        __sub__ [summary]

        Args:
            time_delta (timedelta): [description]

        Returns:
            TimeInterval: [description]
        """
        return self.__add__(-time_delta)

    @classmethod
    def from_str_tuple(cls, str_tuple: Tuple[str, str]) -> TimeInterval:
        return cls(start_dt=parse(str_tuple[0]), end_dt=parse(str_tuple[1]))

    @property
    def time_delta(self) -> timedelta:
        """
        time_delta [summary]

        Returns:
            timedelta: [description]
        """
        return self.end_dt - self.start_dt

    def to_str_tuple(self) -> Tuple[str, str]:
        return str(self.start_dt), str(self.end_dt)


def generate_random_time_delta(
    max_random_shift: timedelta, min_random_shift: timedelta
) -> timedelta:
    """
    generate_random_time_delta samples a random timedelta from the uniform distribution
    in the range (min_random_shift, max_random_shift)

    Args:
        max_random_shift (timedelta): [description]
        min_random_shift (timedelta): [description]

    Returns:
        timedelta: [description]
    """
    return timedelta(
        seconds=max_random_shift.total_seconds() * random()
        + min_random_shift.total_seconds()
    )


def generate_lookback_intervals(
    latest_time_interval: TimeInterval,
    num_lookback_intervals: int,
    lookback_timedelta: timedelta = timedelta(days=1),
    num_copies: int = 1,
    reverse: bool = True,
) -> List[TimeInterval]:
    """
    generate_lookback_intervals creates a List of num_lookback_intervals TimeInterval
    objects starting from latest_time_interval, separated by lookback_timedelta. This
    is used by RLLib actors to create mutliple environments for different lookback days.

    Args:
        latest_time_interval (TimeInterval): Point in time from which to start generating lookbacks
        num_lookback_intervals (int): Number of lookback TimeInterval objects to generate.
        lookback_timedelta (timedelta, optional): Length of time which to separate lookback
            intervals. Defaults to timedelta(days=1).
        num_copies (int, optional): Number of copies of each TimeInterval to include. Defaults to 1.
        reverse (bool, optional): Return in descending order if true

    Returns:
        List[TimeInterval]: [description]
    """
    time_intervals = num_copies * [
        latest_time_interval - n * lookback_timedelta
        for n in range(num_lookback_intervals + 1)
    ]

    if not reverse:
        time_intervals.sort(key=lambda time_interval: time_interval.start_dt)

    return time_intervals


def generate_randomly_shifted_lookback_intervals(
    latest_time_interval: TimeInterval,
    num_lookback_intervals: int,
    lookback_timedelta: timedelta = timedelta(days=1),
    max_random_shift: timedelta = timedelta(0),
    min_random_shift: timedelta = timedelta(0),
    num_copies: int = 1,
) -> List[TimeInterval]:
    """
    generate_randomly_shifted_lookback_intervals generate lookback intervals and adds uniformly
    sampled random shifts

    Args:
        latest_time_interval (TimeInterval): Point in time from which to start generating lookbacks
        num_lookback_intervals (int): Number of lookback TimeInterval objects to generate.
        lookback_timedelta (timedelta, optional): Length of time which to separate lookback
            intervals. Defaults to timedelta(days=1).
        max_random_shift (timedelta, optional): max_random_shift to add. Defaults to timedelta(0).
        min_random_shift (timedelta, optional): min_random_shift to add. Defaults to timedelta(0).
        num_copies (int, optional): Number of copies of each TimeInterval to include. Defaults to 1.

    Returns:
        List[TimeInterval]: [description]
    """
    lookback_intervals = generate_lookback_intervals(
        latest_time_interval, num_lookback_intervals, lookback_timedelta, num_copies
    )

    return [
        lookback_interval
        + generate_random_time_delta(max_random_shift, min_random_shift)
        for lookback_interval in lookback_intervals
    ]
