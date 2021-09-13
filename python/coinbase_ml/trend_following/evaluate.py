from datetime import timedelta
from typing import List, Dict, Any

from dateutil.parser import parse
from google.protobuf import json_format

from coinbase_ml.common import constants as cc
from coinbase_ml.common.featurizers import build_featurizer_configs
from coinbase_ml.common.protos.environment_pb2 import RewardStrategy
from coinbase_ml.common.protos.featurizers_pb2 import Featurizer
from coinbase_ml.common.utils.ray_utils import get_actionizer
from coinbase_ml.common.utils.time_utils import TimeInterval
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.common.protos.actionizers_pb2 import ActionizerConfigs
from coinbase_ml.fakebase.protos.fakebase_pb2 import SimulationType
from coinbase_ml.trend_following.constants import FAKEBASE_SERVER_PORT, INFO_DICT
from coinbase_ml.trend_following.experiment_configs.constants import SACRED_EXPERIMENT


@SACRED_EXPERIMENT.main
def evaluate(
    actionizer_name: str,
    actionizer_configs: dict,
    end_dt: str,
    featurizer: str,
    featurizer_configs: Dict[str, Any],
    initial_product_funds: str,
    initial_quote_funds: str,
    num_warmup_time_steps: int,
    result_metric: str,
    reward_strategy: str,
    start_dt: str,
    time_delta: int,
) -> float:
    exchange = Exchange(port=FAKEBASE_SERVER_PORT, terminate_automatically=False)
    _featurizer = Featurizer.Value(featurizer)
    _featurizer_configs = build_featurizer_configs(_featurizer, featurizer_configs)

    simulation_id = exchange.start(
        actionizer=get_actionizer(actionizer_name).proto_value,
        actionizer_configs=json_format.ParseDict(
            actionizer_configs, ActionizerConfigs()
        ),
        backup_to_cloud_storage=False,
        enable_progress_bar=False,
        end_dt=parse(end_dt),
        featurizer=_featurizer,
        featurizer_configs=_featurizer_configs,
        initial_product_funds=cc.PRODUCT_ID.product_volume_type(initial_product_funds),
        initial_quote_funds=cc.PRODUCT_ID.quote_volume_type(initial_quote_funds),
        num_warmup_time_steps=num_warmup_time_steps,
        product_id=cc.PRODUCT_ID,
        reward_strategy=RewardStrategy.Value(reward_strategy),
        skip_checkpoint_after_warmup=True,
        skip_database_query=True,
        simulation_type=SimulationType.evaluation,
        start_dt=parse(start_dt),
        time_delta=timedelta(seconds=time_delta),
    ).simulation_id

    exchange.run(simulation_id)
    exchange.stop(simulation_id)

    info_dict = exchange.info_dict(simulation_id)
    SACRED_EXPERIMENT.info[INFO_DICT] = info_dict
    return info_dict[result_metric]


def run(
    command_line_args: List[str],
    actionizer_configs_json: str,
    time_interval: TimeInterval,
) -> Dict[str, float]:
    if not bool(set(["-u", "--unobserved"]).intersection(set(command_line_args))):
        command_line_args.append("-u")

    for i, clause in enumerate(
        [
            f"actionizer_configs={actionizer_configs_json}",
            f"start_dt={time_interval.to_str_tuple()[0]}",
            f"end_dt={time_interval.to_str_tuple()[1]}",
        ],
        start=2,
    ):
        command_line_args.insert(command_line_args.index("with") + i, clause)

    _run = SACRED_EXPERIMENT.run_commandline(["evaluate"] + command_line_args)
    return _run.info[INFO_DICT]
