"""
utils for this module
"""
from decimal import Decimal
from typing import Type


class InvalidTypeError(TypeError):
    """
    InvalidTypeError
    """

    def __init__(self, type_name: Type, var_name: str) -> None:
        """
        InvalidTypeError [summary]

        Args:
            type_name (Type): [description]
            var_name (str): [description]
        """
        super().__init__(f"Type {type_name} of {var_name} is invalid.")


def get_precision_as_decimal(precision: int) -> Decimal:
    """
    get_precision_as_decimal [summary]

    Args:
        precision (int): [description]

    Returns:
        Decimal: [description]
    """
    return Decimal("1." + "".join("0" for _ in range(precision)))
