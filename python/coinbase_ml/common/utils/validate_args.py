"""Validate function inputs with decorators and lambdas
"""

from __future__ import annotations

from typing import Callable, Any


def validate_args(**validators: Callable) -> ValidateArgs:
    """Apply decorator to function to validate input args.

    Validated functions will throw ValueError if constraints are violated.

    Ex:
    @validate_args(x = lambda x : x > 0)
    def sqrt(x: float) -> float:
        return x**0.5

    @validate_args(**{
        "adjacent,hypotenuse": lambda adjacent, hypotenuse: (hypotenuse**2 - adjacent**2) > 0
        })
    def length_of_opposite_triangle_side(adjacent: float, hypotenuse: float) -> float:
        return (hypotenuse**2 - adjacent**2)**0.5
    """
    return ValidateArgs(**validators)


class ValidateArgs:
    """Validator for function args.

    Decorate function with this class to validate their arguments before function is called.
    See `validate_args` for example.
    """

    def __init__(self, **validators: Callable):
        """Instantiate validator decorator with lambda validators
        """
        self.validators = validators

    def __call__(self, func: Callable) -> Callable:
        """Call decorator on function to be validated
        """

        def validated_func(*args: Any, **kwargs: Any) -> Callable:
            self._validate(func, *args, **kwargs)
            return func(*args, **kwargs)

        return validated_func

    def _validate(self, func: Callable, *args: Any, **kwargs: Any) -> None:
        validators = self.validators
        func_arg_names = func.__code__.co_varnames
        for arg_names, validator in validators.items():
            values = []
            for arg_name in [s.strip() for s in arg_names.split(",")]:
                if arg_name in kwargs:
                    values.append(kwargs[arg_name])
                else:
                    i = func_arg_names.index(arg_name)
                    if i < len(args):
                        values.append(args[i])
                    else:
                        continue

            if len(values) == 0:
                continue

            if not validator(*values):
                raise ValueError(
                    f"Value {values[0] if len(values) == 1 else values} "
                    f"is invalid for argument {arg_names}"
                )
