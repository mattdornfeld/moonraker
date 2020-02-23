"""
utils for this module
"""
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
