from __future__ import annotations
from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Tuple

from funcy import compose
from gym.spaces import Box
import tensorflow as tf

# pylint: disable=import-error
from tensorflow.keras import backend as K
from tensorflow.keras.layers import (
    Concatenate,
    Conv2D,
    Input,
    Lambda,
    LeakyReLU,
    Reshape,
)
from tensorflow.keras.models import Model

# pylint: enable=import-error

from ray.rllib.agents.dqn.distributional_q_tf_model import DistributionalQTFModel

from coinbase_ml.common import layers as l
from coinbase_ml.common.observations import ObservationSpace
from coinbase_ml.common.utils import prod


@dataclass
class ModelConfigs:
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
class RainbowModel(DistributionalQTFModel):
    def __init__(
        self,
        obs_space: Box,
        action_space: Box,
        num_outputs: int,
        model_config: Dict[str, Any],
        name: str,
        model_configs: ModelConfigs,
        dueling: bool,
        num_atoms: int,
        use_noisy: bool,
        v_min: float,
        v_max: float,
        sigma0: float,
        **kwargs: Dict[str, Any],
    ):
        super().__init__(
            obs_space=obs_space,
            action_space=action_space,
            num_outputs=model_configs.output_tower_num_units,
            model_config=model_config,
            name=name,
            q_hiddens=(),
            dueling=dueling,
            num_atoms=num_atoms,
            use_noisy=use_noisy,
            v_min=v_min,
            v_max=v_max,
            sigma0=sigma0,
            add_layer_norm=False,
        )

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

        def split(t: tf.Tensor, start: int, end: int) -> tf.Tensor:
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

        self.base_q_model = self._build_q_model(model_configs)
        self.register_variables(self.base_q_model.variables)

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

    def _build_q_model(self, model_configs: ModelConfigs) -> Model:
        q_score = l.DenseBlock(
            depth=model_configs.output_tower_depth,
            units=model_configs.output_tower_num_units,
        )(self._build_base(model_configs))

        return Model(inputs=[self.input_tensor], outputs=[q_score])

    @staticmethod
    def _build_account_funds_tower(
        depth: int, num_units: int
    ) -> Callable[[tf.Tensor], tf.Tensor]:
        return compose(
            l.DenseBlock(depth=depth, units=num_units),
            Lambda(lambda input_tensor: K.squeeze(input_tensor, axis=1)),
        )

    @staticmethod
    def _build_deep_lob_tower(
        attention_dim: int, conv_block_num_filters: int, leaky_relu_slope: float
    ) -> Callable[[tf.Tensor], tf.Tensor]:
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
        model_out = self.base_q_model(input_dict["obs_flat"])
        return model_out, state
