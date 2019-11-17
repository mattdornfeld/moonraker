"""
 [summary]
"""


class EnvironmentFinishedException(Exception):
    """Summary
    """

    def __init__(self, msg: str = None) -> None:
        """Summary

        Args:
            msg (str, optional): Description
        """
        if msg is None:
            msg = (
                "This environment has finished the training episode. "
                "Call self.reset to start a new one."
            )

        super().__init__(msg)


class TrainerNotFoundException(Exception):
    """Summary
    """

    def __init__(self, trainer_name: str) -> None:
        """Summary

        Args:
            trainer_name (str): Description
        """
        msg = f"The Trainer {trainer_name} was not found."

        super().__init__(msg)
