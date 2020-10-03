"""
Creates a Ray serving environment that will host a trained model.
The environment will be queried over the loopback device by controller.py.
"""
# pylint: disable=no-name-in-module,import-error,useless-super-delegation
import logging

from ray.rllib.env import ExternalEnv
from ray.rllib.utils.policy_server import PolicyServer

from coinbase_ml.common.observations import ActionSpace, ObservationSpace
from coinbase_ml.serve import constants as c

LOGGER = logging.getLogger(__name__)


class Environment(ExternalEnv):
    """
    Environment [summary]
    """

    def __init__(
        self,
        action_space: ActionSpace,
        observation_space: ObservationSpace,
        max_concurrent: int = 2,
    ):
        """
        Environment [summary]

        Args:
            action_space (ActionSpace): [description]
            observation_space (ObservationSpace): [description]
            max_concurrent (int, optional): [description]. Defaults to 2.
        """
        super().__init__(action_space, observation_space, max_concurrent)

    def run(self) -> None:
        """
        run [summary]
        """
        LOGGER.info(
            "Starting policy server at %s:%s",
            c.SERVED_POLICY_ADDRESS,
            c.SERVED_POLICY_PORT,
        )
        server = PolicyServer(self, c.SERVED_POLICY_ADDRESS, c.SERVED_POLICY_PORT)
        server.serve_forever()
