"""Summary

Attributes:
    ACCOUNT_FUNDS (tf.Tensor): Description
    MATCHES (tf.Tensor): Description
    ORDER_BOOK (tf.Tensor): Description
    ORDERS (tf.Tensor): Description
"""
from typing import Any, Callable, Dict, List, Optional, Tuple

from funcy import compose
from gym.spaces import Box
import tensorflow as tf

# pylint: disable=import-error
from tensorflow.keras import backend as K
from tensorflow.keras.activations import linear, sigmoid
from tensorflow.keras.layers import (
    Concatenate,
    Conv2D,
    Dense,
    Input,
    Lambda,
    LeakyReLU,
    Reshape,
)
from tensorflow.keras.models import Model

# pylint: enable=import-error

from ray.rllib.agents.ddpg.ddpg_tf_model import DDPGTFModel

from coinbase_ml.common import layers as l
from coinbase_ml.common.observations import ActionSpace, ObservationSpace
from coinbase_ml.common.utils import prod
from coinbase_ml.train.utils.config_utils import HyperParameters


# pylint: disable=abstract-method, unused-argument
class TD3ActorCritic(DDPGTFModel):
    """
    Attributes:
        account_funds (tf.Tensor): Description
        actor (Model): Description
        critic (Model): Description
        order_book (tf.Tensor): Description
        time_series (tf.Tensor): Description
    """

    def __init__(
        self,
        obs_space: Box,
        action_space: ActionSpace,
        num_outputs: int,
        model_config: Dict[str, Any],
        name: str,
        hyper_params: HyperParameters,
        twin_q: bool = True,
        **kwargs: Dict[str, Any],
    ):
        """
        __init__ [summary]

        Args:
            obs_space (Box): [description]
            action_space (ActionSpace): [description]
            num_outputs (int): [description]
            model_config (Dict[str, Any]): [description]
            name (str): [description]
        """
        super().__init__(obs_space, action_space, num_outputs, model_config, name)

        self.value: Optional[tf.Tensor] = None

        self._obs_space: ObservationSpace = obs_space.original_space
        self._account_funds_input_size = int(
            prod(self._obs_space.account_funds_space.shape)
        )
        self._order_book_input_size = int(prod(self._obs_space.order_book_space.shape))
        self._time_series_input_size = int(
            prod(self._obs_space.time_series_space.shape)
        )

        input_shape = (
            self._account_funds_input_size
            + self._order_book_input_size
            + self._time_series_input_size
        )

        self.input_tensor: tf.Tensor = Input(shape=(input_shape,), name="observations")
        self.actions_input = Input(shape=(self.action_dim,), name="actions")

        def split(t: tf.Tensor, start: int, end: int) -> tf.Tensor:
            """
            split [summary]

            Args:
                t (tf.Tensor): [description]
                start (int): [description]
                end (int): [description]

            Returns:
                tf.Tensor: [description]
            """
            return Lambda(lambda t: t[:, start:end])(t)

        account_funds = split(
            t=self.input_tensor, start=0, end=self._account_funds_input_size
        )

        order_book = split(
            t=self.input_tensor,
            start=self._account_funds_input_size,
            end=self._account_funds_input_size + self._order_book_input_size,
        )

        time_series = split(
            t=self.input_tensor,
            start=self._account_funds_input_size + self._order_book_input_size,
            end=input_shape,
        )

        self.account_funds = Reshape(self._obs_space.account_funds_space.shape)(
            account_funds
        )
        self.order_book = Reshape(self._obs_space.order_book_space.shape)(order_book)
        self.time_series = Reshape(self._obs_space.time_series_space.shape)(time_series)

        self.policy_model = self._build_policy_model(hyper_params, num_outputs)
        self.register_variables(self.policy_model.variables)

        self.q_model = self._build_q_model(hyper_params)
        self.register_variables(self.q_model.variables)

        if twin_q:
            self.twin_q_model = self._build_q_model(hyper_params)
            self.register_variables(self.twin_q_model.variables)
        else:
            self.twin_q_model = None

    def _build_base(self, hyper_params: HyperParameters) -> tf.Tensor:
        account_funds_branch = self._build_account_funds_tower(
            depth=hyper_params.account_funds_tower_depth,
            num_units=hyper_params.account_funds_num_units,
        )(self.account_funds)

        deep_lob_branch = self._build_deep_lob_tower(
            attention_dim=hyper_params.deep_lob_tower_attention_dim,
            conv_block_num_filters=hyper_params.deep_lob_tower_conv_block_num_filters,
            leaky_relu_slope=hyper_params.deep_lob_tower_leaky_relu_slope,
        )(self.order_book)

        time_series_branch = self._build_time_series_tower(
            attention_dim=hyper_params.time_series_tower_attention_dim,
            depth=hyper_params.time_series_tower_depth,
            num_filters=hyper_params.time_series_tower_num_filters,
            num_stacks=hyper_params.time_series_tower_num_stacks,
        )([deep_lob_branch, self.time_series])

        return Concatenate(axis=-1)([account_funds_branch, time_series_branch])

    def _build_policy_model(
        self, hyper_params: HyperParameters, num_outputs: int
    ) -> Model:
        merged_output_branch = self._build_base(hyper_params)

        actions = compose(
            Dense(self.action_dim, activation=sigmoid),
            l.DenseBlock(
                depth=hyper_params.output_tower_depth,
                units=hyper_params.output_tower_num_units,
            ),
        )(merged_output_branch)

        return Model(inputs=self.input_tensor, outputs=[actions])

    def _build_q_model(self, hyper_params: HyperParameters) -> Model:
        merged_output_branch = Concatenate(axis=-1)(
            [self._build_base(hyper_params), self.actions_input]
        )

        q_score = compose(
            Dense(1, activation=linear),
            l.DenseBlock(
                depth=hyper_params.output_tower_depth,
                units=hyper_params.output_tower_num_units,
            ),
        )(merged_output_branch)

        return Model(inputs=[self.input_tensor, self.actions_input], outputs=[q_score])

    @staticmethod
    def _build_account_funds_tower(
        depth: int, num_units: int
    ) -> Callable[[tf.Tensor], tf.Tensor]:
        """
        _build_account_funds_tower [summary]

        Args:
            depth (int): [description]
            num_units (int): [description]

        Returns:
            Callable[[tf.Tensor], tf.Tensor]: [description]
        """
        return compose(
            l.DenseBlock(depth=depth, units=num_units),
            Lambda(lambda input_tensor: K.squeeze(input_tensor, axis=1)),
        )

    @staticmethod
    def _build_deep_lob_tower(
        attention_dim: int, conv_block_num_filters: int, leaky_relu_slope: float
    ) -> Callable[[tf.Tensor], tf.Tensor]:
        """
        _build_deep_lob_tower [summary]

        Args:
            attention_dim (int): [description]
            conv_block_num_filters (int): [description]
            leaky_relu_slope (float): [description]

        Returns:
            Callable[[tf.Tensor], tf.Tensor]: [description]
        """
        return compose(
            l.InceptionModule(filters=32, leaky_relu_slope=leaky_relu_slope),
            LeakyReLU(leaky_relu_slope),
            l.FullConvolutionBlock1D(
                depth=2, filters=conv_block_num_filters, kernel_size=4, padding="same"
            ),
            l.Attention(attention_dim=attention_dim),
            LeakyReLU(leaky_relu_slope),
            l.FullConvolutionBlock(
                depth=2,
                filters=conv_block_num_filters,
                kernel_size=(4, 1),
                padding="same",
            ),
            LeakyReLU(leaky_relu_slope),
            Conv2D(
                filters=conv_block_num_filters,
                kernel_size=(1, 2),
                padding="same",
                strides=(1, 2),
            ),
            LeakyReLU(leaky_relu_slope),
            l.FullConvolutionBlock(
                depth=2,
                filters=conv_block_num_filters,
                kernel_size=(4, 1),
                padding="same",
            ),
            LeakyReLU(leaky_relu_slope),
            Conv2D(
                filters=conv_block_num_filters,
                kernel_size=(1, 2),
                padding="same",
                strides=(1, 2),
            ),
            Lambda(lambda order_book: K.expand_dims(order_book, axis=-1)),
        )

    @staticmethod
    def _build_time_series_tower(
        attention_dim: int, depth: int, num_filters: int, num_stacks: int
    ) -> Callable[[List[tf.Tensor]], tf.Tensor]:
        """
        _build_time_series_tower [summary]

        Args:
            attention_dim (int): [description]
            depth (int): [description]
            num_filters (int): [description]
            num_stacks (int): [description]

        Returns:
            Callable[[List[tf.Tensor]], tf.Tensor]: [description]
        """
        return compose(
            l.Attention(attention_dim),
            l.AtrousConvolutionBlock(
                causal=False,
                dilation_depth=depth,
                nb_filters=num_filters,
                nb_stacks=num_stacks,
                time_distributed=False,
                use_skip_connections=True,
            ),
            Concatenate(),
        )

    def import_from_h5(self, h5_file: str) -> None:
        """Imports weights from an h5 file. Not implemented."""

    def forward(
        self, input_dict: Dict[str, Any], state: List, seq_lens: tf.Tensor
    ) -> Tuple[tf.Tensor, List]:
        """
        forward The Policy will directly call policy_model to get an action.
        So just return flattened observation here.

        Args:
            input_dict (Dict[str, Any]): [description]
            state (List): [description]
            seq_lens (tf.Tensor): [description]

        Returns:
            Tuple[tf.Tensor, List]: [description]
        """
        return input_dict["obs_flat"], state
