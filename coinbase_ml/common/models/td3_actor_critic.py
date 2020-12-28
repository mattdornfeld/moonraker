"""Summary

Attributes:
    ACCOUNT_FUNDS (tf.Tensor): Description
    MATCHES (tf.Tensor): Description
    ORDER_BOOK (tf.Tensor): Description
    ORDERS (tf.Tensor): Description
"""
from __future__ import annotations
from dataclasses import dataclass
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
from coinbase_ml.common.observations import ObservationSpace
from coinbase_ml.common.utils import prod


@dataclass
class ModelConfigs:
    """ModelConfigs class for apex-td3 model
    """

    account_funds_num_units: int
    account_funds_tower_depth: int
    deep_lob_tower_attention_dim: int
    deep_lob_tower_conv_block_num_filters: int
    deep_lob_tower_leaky_relu_slope: float
    output_tower_depth: int
    output_tower_num_units: int
    time_series_tower_attention_dim: int
    time_series_tower_depth: int
    time_series_tower_num_filters: int
    time_series_tower_num_stacks: int

    @classmethod
    def from_sacred_config(cls, model_configs: dict) -> ModelConfigs:
        return cls(**model_configs)


# pylint: disable=abstract-method, unused-argument
class TD3ActorCritic(DDPGTFModel):
    """Model for use with TD3
    """

    def __init__(
        self,
        obs_space: Box,
        action_space: Box,
        num_outputs: int,
        model_config: Dict[str, Any],
        name: str,
        model_configs: ModelConfigs,
        twin_q: bool = True,
        **kwargs: Dict[str, Any],
    ):
        """
        __init__ [summary]

        Args:
            obs_space (Box): [description]
            action_space (Box): [description]
            num_outputs (int): [description]
            model_config (Dict[str, Any]): [description]
            name (str): [description]
            model_configs (ModelConfigs): [description]
            twin_q (bool, optional): [description]. Defaults to True.

        Returns:
            [type]: [description]
        """
        # from IPython import embed; embed()
        self.action_dim = action_space.shape[0]
        super(DDPGTFModel, self).__init__(  # pylint: disable=bad-super-call
            obs_space, action_space, num_outputs, model_config, name
        )

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

        self.policy_model = self._build_policy_model(model_configs, num_outputs)
        self.register_variables(self.policy_model.variables)

        self.q_model = self._build_q_model(model_configs)
        self.register_variables(self.q_model.variables)

        if twin_q:
            self.twin_q_model = self._build_q_model(model_configs)
            self.register_variables(self.twin_q_model.variables)
        else:
            self.twin_q_model = None

    def _build_base(self, model_configs: ModelConfigs) -> tf.Tensor:
        account_funds_branch = self._build_account_funds_tower(
            depth=model_configs.account_funds_tower_depth,
            num_units=model_configs.account_funds_num_units,
        )(self.account_funds)

        deep_lob_branch = self._build_deep_lob_tower(
            attention_dim=model_configs.deep_lob_tower_attention_dim,
            conv_block_num_filters=model_configs.deep_lob_tower_conv_block_num_filters,
            leaky_relu_slope=model_configs.deep_lob_tower_leaky_relu_slope,
        )(self.order_book)

        time_series_branch = self._build_time_series_tower(
            attention_dim=model_configs.time_series_tower_attention_dim,
            depth=model_configs.time_series_tower_depth,
            num_filters=model_configs.time_series_tower_num_filters,
            num_stacks=model_configs.time_series_tower_num_stacks,
        )([deep_lob_branch, self.time_series])

        return Concatenate(axis=-1)([account_funds_branch, time_series_branch])

    def _build_policy_model(
        self, model_configs: ModelConfigs, num_outputs: int
    ) -> Model:
        merged_output_branch = self._build_base(model_configs)

        actions = compose(
            Dense(self.action_dim, activation=sigmoid),
            l.DenseBlock(
                depth=model_configs.output_tower_depth,
                units=model_configs.output_tower_num_units,
            ),
        )(merged_output_branch)

        return Model(inputs=self.input_tensor, outputs=[actions])

    def _build_q_model(self, model_configs: ModelConfigs) -> Model:
        merged_output_branch = Concatenate(axis=-1)(
            [self._build_base(model_configs), self.actions_input]
        )

        q_score = compose(
            Dense(1, activation=linear),
            l.DenseBlock(
                depth=model_configs.output_tower_depth,
                units=model_configs.output_tower_num_units,
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
