from datetime import timedelta
from typing import List

from dateutil.parser import parse

from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.trend_following.constants import FAKEBASE_SERVER_PORT
from coinbase_ml.trend_following.experiment_configs.constants import SACRED_EXPERIMENT


@SACRED_EXPERIMENT.main
def poplate_storage(start_dt: str, end_dt: str, time_delta: int,) -> None:
    exchange = Exchange(port=FAKEBASE_SERVER_PORT, terminate_automatically=False)
    exchange.populate_storage(
        start_time=parse(start_dt),
        end_time=parse(end_dt),
        time_delta=timedelta(seconds=time_delta),
    )


def run(command_line_args: List[str]) -> None:
    if not bool(set(["-u", "--unobserved"]).intersection(set(command_line_args))):
        command_line_args.append("-u")

    SACRED_EXPERIMENT.run_commandline(["populate_storage"] + command_line_args)
