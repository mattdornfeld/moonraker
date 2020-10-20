"""Sets up a background Flask server, which runs in the driver process,
to accept the contents of a results dict and log it to Sacred. This is
necessary because the SACRED_EXPERIMENT object only exists in the driver
process and the rllib callbacks are run in the trainer process.
"""
import json
from threading import Thread

from flask import Flask, request

from coinbase_ml.common import constants as cc
from coinbase_ml.common.utils.sacred_utils import log_metrics_to_sacred
from coinbase_ml.train.experiment_configs.common import SACRED_EXPERIMENT

APP = Flask("SacredLogger")


@APP.route("/", methods=["POST"])
def log_to_sacred() -> str:
    """Log results dict to sacred
    """
    result = json.loads(request.get_json())
    log_metrics_to_sacred(SACRED_EXPERIMENT, result["custom_metrics"], prefix="train")
    log_metrics_to_sacred(
        SACRED_EXPERIMENT, result["info"]["learner"]["default_policy"], prefix="train",
    )
    log_metrics_to_sacred(
        SACRED_EXPERIMENT, result["evaluation_custom_metrics"], prefix="evaluation"
    )
    return "logging successful"


class SacredLogger(Thread):
    """Runs SacredLogger service in background thread
    """

    def __init__(self) -> None:
        """Construct daemon thread
        """
        super().__init__(daemon=True)

    def run(self) -> None:
        """Runs SacredLogger service in background thread
        """
        APP.run(host="0.0.0.0", port=cc.SACRED_LOGGER_PORT)
