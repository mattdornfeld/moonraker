from datetime import timedelta
from typing import List, Tuple, Set

from coinbase_ml.common.utils.time_utils import TimeInterval
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.trend_following.constants import FAKEBASE_SERVER_PORT
from coinbase_ml.trend_following.experiment_configs.constants import SACRED_EXPERIMENT


@SACRED_EXPERIMENT.main
def populate_storage(
    evaluate_time_intervals: List[Tuple[str, str]],
    optimize_time_intervals: List[Tuple[str, str]],
    time_delta: int,
) -> None:
    time_intervals: Set[TimeInterval] = set()
    for time_interval_tuple in evaluate_time_intervals + optimize_time_intervals:
        time_intervals.add(TimeInterval.from_str_tuple(time_interval_tuple))

    exchange = Exchange(port=FAKEBASE_SERVER_PORT, terminate_automatically=False)
    for time_interval in time_intervals:
        exchange.populate_storage(
            start_time=time_interval.start_dt,
            end_time=time_interval.end_dt,
            time_delta=timedelta(seconds=time_delta),
        )


def run(command_line_args: List[str]) -> None:
    if not bool(set(["-u", "--unobserved"]).intersection(set(command_line_args))):
        command_line_args.append("-u")

    SACRED_EXPERIMENT.run_commandline(["populate_storage"] + command_line_args)
