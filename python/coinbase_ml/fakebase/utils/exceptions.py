"""
Exceptions for the package
"""


class ExchangeFinishedException(Exception):

    """Summary
    """

    def __init__(self, msg: str = None) -> None:
        """Summary

        Args:
            msg (str, optional): Description
        """
        if msg is None:
            msg = "The Exchange has reached the end of the simulation period."

        super().__init__(msg)


class IllegalTransactionException(Exception):
    """Summary
    """


class OrderNotFoundException(Exception):

    """Summary
    """

    def __init__(self, order_id: str) -> None:
        """Summary

        Args:
            msg (str, optional): Description
        """
        msg = f"Order {order_id} was not found on the order book."

        super().__init__(msg)
