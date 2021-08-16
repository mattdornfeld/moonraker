import json
import sys
from typing import List, Dict, Tuple, Optional
from uuid import uuid4

from funcy import func_partial
from ray import tune
from ray.tune import CLIReporter, ExperimentAnalysis
from ray.tune.sample import Sampler
from sacred.run import Run

from coinbase_ml.common.utils.ray_utils import (
    get_search_algorithm,
    ray_init,
    eval_tune_config,
)
from coinbase_ml.common.utils.time_utils import TimeInterval
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.trend_following.constants import (
    FAKEBASE_SERVER_PORT,
    INFO_DICT,
    PROCESSED_SEARCH_CONFIG,
)
from coinbase_ml.trend_following.experiment_configs.constants import SACRED_EXPERIMENT


def evaluate_for_search_config(
    search_config: Dict[str, float],
    command_line_args: List[str],
    tune_config: Dict[str, Sampler],
    actionizer_name: str,
    time_interval: TimeInterval,
) -> Tuple[Dict[str, float], dict]:
    # pylint: disable=import-outside-toplevel
    from google.protobuf import json_format
    from coinbase_ml.common.utils.ray_utils import get_actionizer
    import coinbase_ml.trend_following.evaluate as evaluate

    # pylint: enable=import-outside-toplevel

    actionizer_configs = get_actionizer(actionizer_name).build_actionizer_configs(
        search_config, tune_config
    )
    actionizer_configs_json: str = json_format.MessageToJson(actionizer_configs)
    actionizer_configs_dict: dict = json_format.MessageToDict(actionizer_configs)
    actionizer_name = actionizer_configs.WhichOneof("sealed_value")

    return (
        evaluate.run(command_line_args, actionizer_configs_json, time_interval),
        actionizer_configs_dict[actionizer_name],
    )


def optimize_objective(
    search_config: Dict[str, float],
    checkpoint_dir: str,  # pylint: disable=unused-argument
    command_line_args: List[str],
    result_metric_name: str,
    tune_config: Dict[str, Sampler],
    actionizer_name: str,
    time_interval: TimeInterval,
) -> None:
    info_dict, processed_search_config = evaluate_for_search_config(
        search_config, command_line_args, tune_config, actionizer_name, time_interval
    )

    tune.report(
        **{
            result_metric_name: info_dict[result_metric_name],
            INFO_DICT: info_dict,
            PROCESSED_SEARCH_CONFIG: processed_search_config,
        }
    )


@SACRED_EXPERIMENT.main
def optimize(
    _run: Run,
    actionizer_name: str,
    evaluate_time_intervals: List[Tuple[str, str]],
    num_samples: int,
    optimization_mode: str,
    optimize_time_intervals: List[Tuple[str, str]],
    resources_per_trial: dict,
    result_metric: str,
    search_algorithm: str,
    search_algorithm_config: dict,
    tune_config: Dict[str, str],
) -> float:
    try:
        ray_init()
        _tune_config: Dict[str, Sampler] = eval_tune_config(tune_config)

        command_line_args = sys.argv[1:]
        experiment_analysis: Optional[ExperimentAnalysis] = None
        best_configs: List[Dict[str, float]] = []
        best_optimize_info_dicts: List[Dict[str, float]] = []
        best_evaluate_info_dicts: List[Dict[str, float]] = []
        for optimize_time_interval, evaluate_time_interval in zip(
            optimize_time_intervals, evaluate_time_intervals
        ):
            if experiment_analysis is None:
                _search_algorithm_config = search_algorithm_config
            else:
                _search_algorithm_config = {
                    "n_initial_points": 0,
                    "points_to_evaluate": [experiment_analysis.best_config],
                    **search_algorithm_config,
                }

            reporter = CLIReporter()
            reporter.add_metric_column(PROCESSED_SEARCH_CONFIG)
            reporter.add_metric_column(result_metric)

            experiment_analysis = tune.run(
                name=f"trend_following_{uuid4()}",
                run_or_experiment=func_partial(
                    optimize_objective,
                    command_line_args=command_line_args,
                    result_metric_name=result_metric,
                    tune_config=_tune_config,
                    actionizer_name=actionizer_name,
                    time_interval=TimeInterval.from_str_tuple(optimize_time_interval),
                ),
                config=_tune_config,
                max_failures=3,
                metric=result_metric,
                mode=optimization_mode,
                num_samples=num_samples,
                progress_reporter=reporter,
                resources_per_trial=resources_per_trial,
                search_alg=get_search_algorithm(
                    search_algorithm, _search_algorithm_config
                ),
                stop={"training_iteration": 1},
                reuse_actors=True,
            )

            (
                evaluation_info_dict,
                best_processed_search_config,
            ) = evaluate_for_search_config(
                experiment_analysis.best_config,
                command_line_args,
                _tune_config,
                actionizer_name,
                TimeInterval.from_str_tuple(evaluate_time_interval),
            )

            best_configs.append(best_processed_search_config)
            best_optimize_info_dicts.append(
                experiment_analysis.best_result["info_dict"]
            )
            best_evaluate_info_dicts.append(evaluation_info_dict)

            SACRED_EXPERIMENT.log_scalar(
                f"evaluation_{result_metric}", evaluation_info_dict[result_metric]
            )
            SACRED_EXPERIMENT.log_scalar(
                f"optimization_{result_metric}",
                experiment_analysis.best_result["info_dict"][result_metric],
            )
            for parameter_name, parameter_value in best_processed_search_config.items():
                SACRED_EXPERIMENT.log_scalar(parameter_name, parameter_value)

    finally:
        Exchange(port=FAKEBASE_SERVER_PORT, health_check=False).shutdown()

        _run.info["best_configs"] = json.dumps(best_configs)
        _run.info["best_evaluate_info_dicts"] = json.dumps(best_evaluate_info_dicts)
        _run.info["best_optimize_info_dicts"] = json.dumps(best_optimize_info_dicts)

    return evaluation_info_dict[result_metric]


if __name__ == "__main__":
    SACRED_EXPERIMENT.run_commandline()
