"""Summary
"""
from itertools import product
from typing import Any, Dict, Tuple, Sequence, Union

from funcy import compose
import tensorflow as tf
from tensorflow.keras.layers import (  # pylint: disable=E0401
    Add,
    Concatenate,
    Conv1D,
    Conv2D,
    Dense,
    Layer,
    LeakyReLU,
    MaxPool1D,
    Multiply,
    TimeDistributed,
)

from coinbase_ml.common import utils


class AtrousConvolutionBlock:
    """Summary

    Attributes:
        dilation_depth (TYPE): Description
        nb_filters (TYPE): Description
        nb_stacks (TYPE): Description
        use_skip_connections (TYPE): Description
    """

    def __init__(
        self,
        causal: bool,
        dilation_depth: int,
        nb_filters: int,
        nb_stacks: int,
        time_distributed: bool,
        use_skip_connections: bool,
    ) -> None:
        """
        __init__ [summary]

        Args:
            causal (bool): [description]
            dilation_depth (int): [description]
            nb_filters (int): [description]
            nb_stacks (int): [description]
            time_distributed (bool): [description]
            use_skip_connections (bool): [description]

        Returns:
            None: [description]
        """
        self.dilation_depth = dilation_depth
        self.nb_filters = nb_filters
        self.nb_stacks = nb_stacks
        self.padding = "causal" if causal else "same"
        self.time_distributed = time_distributed
        self.use_skip_connections = use_skip_connections
        self.Conv1D = TDConv1D if time_distributed else Conv1D  # pylint: disable=C0103

    def __call__(self, input_tensor: tf.Tensor) -> tf.Tensor:
        """
        __call__ [summary]

        Args:
            input_tensor (tf.Tensor): [description]

        Returns:
            tf.Tensor: [description]
        """
        skip_connections = []

        _output = self.Conv1D(
            dilation_rate=1,
            filters=self.nb_filters,
            kernel_size=2,
            padding=self.padding,
            use_bias=True,
        )(input_tensor)

        gen = compose(utils.all_but_last, product)(
            range(self.nb_stacks), range(0, self.dilation_depth + 1)
        )

        i = 0
        for _, i in gen:
            _output, skip_output = self._create_residual_block(
                dilation=2 ** i,
                input_tensor=_output,
                last_block=False,
                nb_filters=self.nb_filters,
            )

            skip_connections.append(skip_output)

        _output, skip_output = self._create_residual_block(
            dilation=2 ** (i + 1),
            input_tensor=_output,
            last_block=True,
            nb_filters=self.nb_filters,
        )

        skip_connections.append(skip_output)

        return Add()(skip_connections) if self.use_skip_connections else _output

    def _create_residual_block(
        self, dilation: int, input_tensor: tf.Tensor, last_block: bool, nb_filters: int
    ) -> Tuple[tf.Tensor, tf.Tensor]:
        """
        _create_residual_block [summary]

        Args:
            dilation (int): [description]
            input_tensor (tf.Tensor): [description]
            last_block (bool): [description]
            nb_filters (int): [description]

        Returns:
            Tuple[tf.Tensor, tf.Tensor]: [description]
        """
        original_input_tensor = input_tensor

        tanh_out = self.Conv1D(
            activation="tanh",
            dilation_rate=dilation,
            filters=nb_filters,
            kernel_size=2,
            padding=self.padding,
            use_bias=True,
        )(input_tensor)

        sigmoid_out = self.Conv1D(
            activation="sigmoid",
            dilation_rate=dilation,
            filters=nb_filters,
            kernel_size=2,
            padding=self.padding,
            use_bias=True,
        )(input_tensor)

        gated_tensor = Multiply()([tanh_out, sigmoid_out])

        if not last_block:
            _resisudal = self.Conv1D(
                filters=nb_filters, kernel_size=1, padding="same", use_bias=True
            )(gated_tensor)

            resisudal = Add()([original_input_tensor, _resisudal])
        else:
            resisudal = original_input_tensor

        skip = self.Conv1D(
            filters=nb_filters, kernel_size=1, padding="same", use_bias=True
        )(gated_tensor)

        return resisudal, skip


class Attention(Layer):

    """Summary

    Attributes:
        attention_dim (int): Description
        b_w (tf.Tensor, optional): Description
        u_w (tf.Tensor, optional): Description
        W_w (tf.Tensor, optional): Description
    """

    def __init__(self, attention_dim: int) -> None:
        """Summary

        Args:
            attention_dim (int): Description
        """
        self.attention_dim = attention_dim
        self.W_w = None  # pylint: disable=C0103
        self.b_w = None
        self.u_w = None

        super().__init__()

    @staticmethod
    def _matmul(W: tf.Tensor, h: tf.Tensor) -> tf.Tensor:  # pylint: disable=C0103
        """Summary

        Args:
            W (tf.Tensor): Description
            h (tf.Tensor): Description

        Returns:
            tf.Tensor: Description
        """
        return tf.reduce_sum(tf.expand_dims(h, axis=-2) * W, axis=-1)

    def build(self, input_shape: Sequence[int]) -> None:
        """Summary

        Args:
            input_shape (Sequence[int]): Description
        """
        self.W_w = self.add_weight(
            name="W_w",
            shape=(self.attention_dim, int(input_shape[-1])),
            initializer=tf.random.normal,
            trainable=True,
        ).value()

        self.b_w = self.add_weight(
            name="b_w",
            shape=(self.attention_dim,),
            initializer=tf.random.normal,
            trainable=True,
        ).value()

        self.u_w = self.add_weight(
            name="u_w",
            shape=(self.attention_dim,),
            initializer=tf.random.normal,
            trainable=True,
        ).value()

        super().build(input_shape)

    def call(self, h: tf.Tensor) -> tf.Tensor:  # pylint: disable=W0221
        """Summary

        Args:
            h (tf.Tensor): Description

        Returns:
            tf.Tensor: Description
        """
        u = tf.tanh(self._matmul(self.W_w, h) + self.b_w)  # pylint: disable=C0103

        numerator = tf.reduce_sum(self.u_w * u, axis=-1)

        denominator = tf.reduce_sum(tf.exp(numerator), axis=-1)

        alpha = tf.exp(numerator) / tf.expand_dims(denominator, axis=-1)

        s = tf.reduce_sum(
            tf.expand_dims(alpha, axis=-1) * h, axis=-2
        )  # pylint: disable=C0103

        return s

    def compute_output_shape(  # pylint: disable=R0201
        self, input_shape: Sequence[int]
    ) -> Sequence[int]:
        """Summary

        Args:
            input_shape (Sequence[int]): Description

        Returns:
            Sequence[int]: Description
        """
        return (*input_shape[0:-2], input_shape[-1])

    def get_config(self) -> Dict[str, Any]:
        """Summary

        Returns:
            Dict[str, Any]: Description
        """
        return {"attention_dim": self.attention_dim}


class DenseBlock:

    """Summary

    Attributes:
        Dense (TYPE): Description
        depth (int): Description
        time_distributed (bool): Description
        units (int): Description
    """

    def __init__(self, depth: int, units: int, time_distributed: bool = False) -> None:
        """
        __init__ [summary]

        Args:
            depth (int): [description]
            units (int): [description]
            time_distributed (bool, optional): [description]. Defaults to False.

        Returns:
            None: [description]
        """
        self.depth = depth
        self.units = units
        self.time_distributed = time_distributed
        self.Dense = TDDense if time_distributed else Dense  # pylint: disable=C0103

    def __call__(self, input_tensor: tf.Tensor) -> tf.Tensor:
        """Summary

        Args:
            input_tensor (tf.Tensor): Description

        Returns:
            Union[tf.Tensor, TimeDistributed]: Description
        """
        layers = [self.Dense(units=self.units) for _ in range(self.depth)]

        return compose(*layers)(input_tensor)


class FullConvolutionBlock:
    """Summary

    Attributes:
        args (TYPE): Description
        depth (int): Description
        kwargs (TYPE): Description
        time_distributed (bool): Description
    """

    def __init__(
        self, depth: int, *args: Any, time_distributed: bool = False, **kwargs: Any
    ) -> None:
        """
        __init__ [summary]

        Args:
            depth (int): [description]
            time_distributed (bool, optional): [description]. Defaults to False.

        Returns:
            None: [description]
        """
        self.args = args
        self.depth = depth
        self.kwargs = kwargs
        self.time_distributed = time_distributed

    def __call__(self, input_tensor: tf.Tensor) -> Union[tf.Tensor, TimeDistributed]:
        """Summary

        Args:
            input_tensor (tf.Tensor): Description

        Returns:
            Union[tf.Tensor, TimeDistributed]: Description
        """
        _Conv2D = (  # pylint: disable=E0601,C0103
            TDConv2D if self.time_distributed else Conv2D
        )

        layers = [
            _Conv2D(*self.args, **self.kwargs) for _ in range(self.depth)
        ]  # pylint: disable=C0330

        return compose(*layers)(input_tensor)


class FullConvolutionBlock1D:
    """Summary

    Attributes:
        args (TYPE): Description
        depth (int): Description
        kwargs (TYPE): Description
        time_distributed (bool): Description
    """

    def __init__(
        self, depth: int, *args: Any, time_distributed: bool = False, **kwargs: Any
    ) -> None:
        """
        __init__ [summary]

        Args:
            depth (int): [description]
            time_distributed (bool, optional): [description]. Defaults to False.

        Returns:
            None: [description]
        """
        self.args = args
        self.depth = depth
        self.kwargs = kwargs
        self.time_distributed = time_distributed

    def __call__(self, input_tensor: tf.Tensor) -> Union[tf.Tensor, TimeDistributed]:
        """Summary

        Args:
            input_tensor (tf.Tensor): Description

        Returns:
            Union[tf.Tensor, TimeDistributed]: Description
        """
        _Conv1D = (  # pylint: disable=E0601,C0103
            TDConv1D if self.time_distributed else Conv1D
        )

        layers = [
            _Conv1D(*self.args, **self.kwargs) for _ in range(self.depth)
        ]  # pylint: disable=C0330

        return compose(*layers)(input_tensor)


class InceptionModule:
    """Summary
    """

    def __init__(self, leaky_relu_slope: float, *args: Any, **kwargs: Any) -> None:
        """
        __init__ [summary]

        Args:
            leaky_relu_slope (float): [description]
        """
        self._towers = [
            compose(
                LeakyReLU(leaky_relu_slope),
                Conv1D(kernel_size=3, *args, **kwargs, padding="same"),
                LeakyReLU(leaky_relu_slope),
                Conv1D(kernel_size=1, *args, **kwargs, padding="same"),
            ),
            compose(
                LeakyReLU(leaky_relu_slope),
                Conv1D(kernel_size=5, *args, **kwargs, padding="same"),
                LeakyReLU(leaky_relu_slope),
                Conv1D(kernel_size=1, *args, **kwargs, padding="same"),
            ),
            compose(
                LeakyReLU(leaky_relu_slope),
                Conv1D(kernel_size=5, *args, **kwargs, padding="same"),
                MaxPool1D(pool_size=1, padding="same"),
            ),
        ]

    def __call__(self, input_tensor: tf.Tensor) -> tf.Tensor:
        """
        __call__ [summary]

        Args:
            input_tensor (tf.Tensor): [description]

        Returns:
            tf.Tensor: [description]
        """
        return Concatenate()([tower(input_tensor) for tower in self._towers])


def TDConv1D(*args: Any, **kwargs: Any) -> TimeDistributed:  # pylint: disable=C0103
    """
    TDConv1D [summary]

    Returns:
        [TimeDistributed]: [description]
    """
    return TimeDistributed(Conv1D(*args, **kwargs))


def TDConv2D(*args: Any, **kwargs: Any) -> TimeDistributed:  # pylint: disable=C0103
    """
    TDConv2D [summary]

    Returns:
        [TimeDistributed]: [description]
    """
    return TimeDistributed(Conv2D(*args, **kwargs))


def TDDense(*args: Any, **kwargs: Any) -> TimeDistributed:  # pylint: disable=C0103
    """Summary

    Args:
        *args: Description
        **kwargs: Description

    Returns:
        TimeDistributed: Description
    """
    return TimeDistributed(Dense(*args, **kwargs))
