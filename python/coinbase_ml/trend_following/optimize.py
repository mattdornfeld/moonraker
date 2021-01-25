import json
import sys
from typing import List, Dict

import ray
from funcy import func_partial
from ray import tune
from sacred.run import Run

from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.trend_following import populate_storage
from coinbase_ml.trend_following.constants import FAKEBASE_SERVER_PORT
from coinbase_ml.trend_following.experiment_configs.constants import SACRED_EXPERIMENT
from coinbase_ml.trend_following.utils import run_function_on_each_ray_node


def optimize_objective(
    search_config: Dict[str, float],
    checkpoint_dir: str,  # pylint: disable=unused-argument
    command_line_args: List[str],
    result_metric_name: str,
) -> None:
    import coinbase_ml.trend_following.evaluate as evaluate  # pylint: disable=import-outside-toplevel

    result_metric = evaluate.run(command_line_args, search_config)
    tune.report(**{result_metric_name: result_metric})


@SACRED_EXPERIMENT.main
def optimize(
    _run: Run,
    optimization_mode: str,
    resources_per_trial: dict,
    result_metric: str,
    search_config: dict,
) -> float:
    try:
        ray.init()
        run_function_on_each_ray_node(populate_storage.run, sys.argv[1:])
        experiment_analysis = tune.run(
            func_partial(
                optimize_objective,
                command_line_args=sys.argv[1:],
                result_metric_name=result_metric,
            ),
            config=search_config,
            metric=result_metric,
            mode=optimization_mode,
            resources_per_trial=resources_per_trial,
            stop={"training_iteration": 1},
            reuse_actors=True,
        )
        for result in experiment_analysis.results.values():
            SACRED_EXPERIMENT.log_scalar(
                json.dumps(result["config"]), result[result_metric]
            )

    finally:
        Exchange(port=FAKEBASE_SERVER_PORT, health_check=False).shutdown()

    _run.info["best_config"] = json.dumps(experiment_analysis.best_result["config"])

    return float(experiment_analysis.best_result[result_metric])


if __name__ == "__main__":
    SACRED_EXPERIMENT.run_commandline()
