import json
from typing import List, Dict

import sys
from funcy import func_partial
from ray import tune
from ray.tune import CLIReporter
from ray.tune.sample import Sampler
from sacred.run import Run

from coinbase_ml.common.utils.ray_utils import get_search_algorithm, ray_init
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.trend_following import populate_storage
from coinbase_ml.trend_following.constants import (
    FAKEBASE_SERVER_PORT,
    INFO_DICT,
    PROCESSED_SEARCH_CONFIG,
)
from coinbase_ml.trend_following.experiment_configs.constants import SACRED_EXPERIMENT
from coinbase_ml.trend_following.utils import run_function_on_each_ray_node


def optimize_objective(
    search_config: Dict[str, float],
    checkpoint_dir: str,  # pylint: disable=unused-argument
    command_line_args: List[str],
    result_metric_name: str,
    tune_config: Dict[str, Sampler],
    actionizer_name: str,
) -> None:
    # pylint: disable=import-outside-toplevel
    from coinbase_ml.common.utils.ray_utils import get_actionizer
    import coinbase_ml.trend_following.evaluate as evaluate

    # pylint: enable=import-outside-toplevel
    processed_search_config = get_actionizer(
        actionizer_name
    ).process_tune_search_config(search_config, tune_config)

    info_dict = evaluate.run(command_line_args, processed_search_config)
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
    num_samples: int,
    optimization_mode: str,
    resources_per_trial: dict,
    result_metric: str,
    search_algorithm: str,
    search_algorithm_config: dict,
    tune_config: Dict[str, str],
) -> float:
    try:
        ray_init()
        run_function_on_each_ray_node(populate_storage.run, sys.argv[1:])
        _tune_config: Dict[str, Sampler] = {
            k: eval(v) for k, v in tune_config.items()  # pylint: disable=eval-used
        }
        reporter = CLIReporter()
        reporter.add_metric_column(PROCESSED_SEARCH_CONFIG)
        reporter.add_metric_column(result_metric)
        experiment_analysis = tune.run(
            func_partial(
                optimize_objective,
                command_line_args=sys.argv[1:],
                result_metric_name=result_metric,
                tune_config=_tune_config,
                actionizer_name=actionizer_name,
            ),
            config=_tune_config,
            max_failures=3,
            metric=result_metric,
            mode=optimization_mode,
            num_samples=num_samples,
            progress_reporter=reporter,
            resources_per_trial=resources_per_trial,
            search_alg=get_search_algorithm(search_algorithm, search_algorithm_config),
            stop={"training_iteration": 1},
            reuse_actors=True,
        )

        for result in experiment_analysis.results.values():
            SACRED_EXPERIMENT.log_scalar(
                json.dumps(result[PROCESSED_SEARCH_CONFIG]), result[result_metric]
            )

    finally:
        Exchange(port=FAKEBASE_SERVER_PORT, health_check=False).shutdown()

    _run.info["best_config"] = json.dumps(
        experiment_analysis.best_result[PROCESSED_SEARCH_CONFIG]
    )
    _run.info["best_info_dict"] = json.dumps(experiment_analysis.best_result[INFO_DICT])

    return float(experiment_analysis.best_result[result_metric])


if __name__ == "__main__":
    SACRED_EXPERIMENT.run_commandline()
