import json
from datetime import timedelta
from typing import List, Dict

from dateutil.parser import parse

from coinbase_ml.common import constants as cc
from coinbase_ml.common.protos.environment_pb2 import RewardStrategy, Featurizer
from coinbase_ml.common.utils.ray_utils import get_actionizer
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.fakebase.protos.fakebase_pb2 import SimulationType
from coinbase_ml.trend_following.constants import FAKEBASE_SERVER_PORT
from coinbase_ml.trend_following.experiment_configs.constants import SACRED_EXPERIMENT


@SACRED_EXPERIMENT.main
def evaluate(
    actionizer_name: str,
    actionizer_configs: Dict[str, float],
    end_dt: str,
    featurizer: str,
    initial_product_funds: str,
    initial_quote_funds: str,
    num_warmup_time_steps: int,
    result_metric: str,
    reward_strategy: str,
    start_dt: str,
    time_delta: int,
) -> float:
    exchange = Exchange(port=FAKEBASE_SERVER_PORT, terminate_automatically=False)

    simulation_id = exchange.start(
        actionizer=get_actionizer(actionizer_name).proto_value,
        actionizer_configs=actionizer_configs,
        backup_to_cloud_storage=False,
        enable_progress_bar=False,
        end_dt=parse(end_dt),
        featurizer=Featurizer.Value(featurizer),
        initial_product_funds=cc.PRODUCT_ID.product_volume_type(initial_product_funds),
        initial_quote_funds=cc.PRODUCT_ID.quote_volume_type(initial_quote_funds),
        num_warmup_time_steps=num_warmup_time_steps,
        product_id=cc.PRODUCT_ID,
        reward_strategy=RewardStrategy.Value(reward_strategy),
        skip_checkpoint_after_warmup=True,
        skip_database_query=True,
        simulation_type=SimulationType.evaluation,
        snapshot_buffer_size=1,
        start_dt=parse(start_dt),
        time_delta=timedelta(seconds=time_delta),
    ).simulation_id

    exchange.run(simulation_id)
    exchange.stop(simulation_id)

    result = exchange.info_dict(simulation_id)[result_metric]
    return result


def run(command_line_args: List[str], search_config: Dict[str, float]) -> float:
    if not bool(set(["-u", "--unobserved"]).intersection(set(command_line_args))):
        command_line_args.append("-u")

    command_line_args.insert(
        command_line_args.index("with") + 2,
        f"actionizer_configs={json.dumps(search_config)}",
    )

    return SACRED_EXPERIMENT.run_commandline(["evaluate"] + command_line_args).result
