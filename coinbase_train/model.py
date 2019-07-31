"""Summary

Attributes:
    ACCOUNT_FUNDS (tf.Tensor): Description
    MATCHES (tf.Tensor): Description
    ORDER_BOOK (tf.Tensor): Description
    ORDERS (tf.Tensor): Description
"""
from typing import Callable, List

from funcy import compose
from keras.layers import Concatenate, Conv2D, Dense, Input, Lambda, LeakyReLU
from keras.models import Model
from keras import backend as K
import tensorflow as tf

from coinbase_train import constants as c
from coinbase_train import layers as l

class ActorCriticModel:
    """
    Attributes:
        account_funds (tf.Tensor): Description
        actor (Model): Description
        critic (Model): Description
        order_book (tf.Tensor): Description
        time_series (tf.Tensor): Description
    """
    def __init__(self,
                 attention_dim: int,
                 depth: int,
                 num_filters: int,
                 num_stacks: int,
                 num_time_steps: int, ):
        """__init__ [summary]
        
        Args:
            attention_dim (int): [description]
            depth (int): [description]
            num_filters (int): [description]
            num_stacks (int): [description]
            num_time_steps (int): [description]
        """
        self.account_funds = Input( 
            batch_shape=(None, 1, 4), 
            name='account_funds')

        self.order_book = Input( 
            batch_shape=(None, num_time_steps, 4*c.ORDER_BOOK_DEPTH), 
            name='order_book')

        self.time_series = Input( 
            batch_shape=(None, num_time_steps, c.NUM_CHANNELS_IN_TIME_SERIES), 
            name='time_series')
        
        self.actor = self._build_actor(attention_dim, depth, num_filters, num_stacks)
        self.critic = self._build_critic(attention_dim, depth, num_filters, num_stacks)

    @staticmethod
    def _actor_output_activation(input_tensor: tf.Tensor) -> tf.Tensor:
        """actor_output_activation [summary]
        
        Args:
            input_tensor (tf.Tensor): [description]
        
        Returns:
            tf.Tensor: [description]
        """
        def softmax_and_unpack(*input_tensors):
            """softmax_and_unpack [summary]
            
            Returns:
                List[tf.Tensor]: [description]
            """
            _input_tensors = [K.expand_dims(t, axis=-1) for t in input_tensors]
            _soft_max = K.softmax(K.concatenate(_input_tensors))
            _output_tensors = K.tf.unstack(_soft_max, axis=-1)

            return [K.expand_dims(t, axis=-1) for t in _output_tensors]

        transaction_buy, transaction_sell = softmax_and_unpack(input_tensor[:, 0], input_tensor[:, 7])
        
        transaction_percent_funds_mean = K.expand_dims(K.sigmoid(input_tensor[:, 1]), axis=-1)
        transaction_post_only = K.expand_dims(K.sigmoid(input_tensor[:, 2]), axis=-1)
        transaction_price_mean = K.expand_dims(K.sigmoid(input_tensor[:, 3]), axis=-1)
        transaction_price_sigma_cholesky_00 = K.expand_dims(K.sigmoid(input_tensor[:, 4]), axis=-1)
        transaction_price_sigma_cholesky_10 = K.expand_dims(K.sigmoid(input_tensor[:, 5]), axis=-1)
        transaction_price_sigma_cholesky_11 = K.expand_dims(K.sigmoid(input_tensor[:, 6]), axis=-1)
        num_transactions = K.softmax(input_tensor[:, 8:])

        return K.concatenate([transaction_buy, 
                              transaction_percent_funds_mean,
                              transaction_post_only,
                              transaction_price_mean,
                              transaction_price_sigma_cholesky_00,
                              transaction_price_sigma_cholesky_10,
                              transaction_price_sigma_cholesky_11,
                              transaction_sell,
                              num_transactions])

    def _build_actor(self,
                     attention_dim: int,
                     depth: int,
                     num_filters: int,
                     num_stacks: int) -> Model:
        """Summary
        
        Args:
            attention_dim (int): Description
            depth (int): Description
            num_filters (int): Description
            num_stacks (int): Description
        
        Returns:
            Model: Description
        """
        account_funds_branch = self._build_account_funds_tower(
            depth=depth, 
            num_units=num_filters)(self.account_funds)
        
        deep_lob_branch = self._build_deep_lob_tower(attention_dim=attention_dim
            )(self.order_book)

        time_series_branch = self._build_time_series_tower(attention_dim=attention_dim,
                                                           depth=depth,
                                                           num_filters=num_filters,
                                                           num_stacks=num_stacks
                                                           )([deep_lob_branch, self.time_series])

        merged_output_branch = Concatenate(axis=-1)([account_funds_branch,
                                                     time_series_branch]) 

        output = compose(Lambda(self._actor_output_activation), 
                         Dense(c.ACTOR_OUTPUT_DIMENSION),
                         l.DenseBlock(depth=depth, 
                                      units=num_filters)
                        )(merged_output_branch)

        actor = Model(
            inputs=[self.account_funds, self.order_book, self.time_series],
            outputs=[output]
        )

        return actor

    def _build_critic(self, 
                      attention_dim: int,
                      depth: int,
                      num_filters: int,
                      num_stacks: int) -> Model:
        """Summary
        
        Args:
            attention_dim (int): Description
            depth (int): Description
            num_filters (int): Description
            num_stacks (int): Description
        
        Returns:
            Model: Description
        """
        action_input = Input( 
            batch_shape=(None, c.ACTOR_OUTPUT_DIMENSION),
            name='critic_action_input')

        account_funds_branch = self._build_account_funds_tower(depth=depth,
                                                               num_units=num_filters)(self.account_funds)

        deep_lob_branch = self._build_deep_lob_tower(attention_dim=attention_dim)(self.order_book)

        time_series_branch = self._build_time_series_tower(attention_dim=attention_dim,
                                                           depth=depth,
                                                           num_filters=num_filters,
                                                           num_stacks=num_stacks
                                                           )([deep_lob_branch, self.time_series])

        merged_output_branch = Concatenate(axis=-1)([action_input,
                                                     account_funds_branch,
                                                     time_series_branch]) 

        output = compose(
            Dense(1),
            l.DenseBlock(depth=depth, 
                         units=num_filters)
            )(merged_output_branch)

        critic = Model(
            inputs=[action_input, self.account_funds, self.order_book, self.time_series],
            outputs=[output]
            )

        return critic

    def _build_account_funds_tower(
            self, 
            depth: int, 
            num_units: int) -> Callable[[tf.Tensor], tf.Tensor]:
        """
        _build_account_funds_tower [summary]
        
        Args:
            depth (int): [description]
            num_units (int): [description]
        
        Returns:
            Callable[[tf.Tensor], tf.Tensor]: [description]
        """
        return compose(
            l.DenseBlock(
                depth=depth,
                units=num_units),
            Lambda(lambda input_tensor: K.squeeze(input_tensor, axis=1)))

    def _build_deep_lob_tower(self, attention_dim: int) -> Callable[[tf.Tensor], tf.Tensor]:
        """
        _build_deep_lob_branch [summary]
        
        Args:
            attention_dim (int): [description]
        
        Returns:
            Callable[[tf.Tensor], tf.Tensor]: [description]
        """
        return compose(l.InceptionModule(filters=32),
                       LeakyReLU(0.01),
                       l.FullConvolutionBlock1D(
                           depth=2, 
                           filters=16, 
                           kernel_size=4, 
                           padding='same'),
                       l.Attention(attention_dim=attention_dim),
                       LeakyReLU(0.01),
                       l.FullConvolutionBlock(
                           depth=2, 
                           filters=16, 
                           kernel_size=(4, 1), 
                           padding='same'),
                       LeakyReLU(0.01),
                       Conv2D(
                           filters=16, 
                           kernel_size=(1, 2),
                           padding='same', 
                           strides=(1, 2)),
                       LeakyReLU(0.01),
                       l.FullConvolutionBlock(
                           depth=2, 
                           filters=16, 
                           kernel_size=(4, 1), 
                           padding='same'),
                       LeakyReLU(0.01),
                       Conv2D(
                           filters=16, 
                           kernel_size=(1, 2), 
                           padding='same', 
                           strides=(1, 2)),
                       Lambda(lambda order_book: K.expand_dims(order_book, axis=-1)))

    def _build_time_series_tower(
            self, 
            attention_dim: int, 
            depth: int, 
            num_filters: int, 
            num_stacks: int) -> Callable[[List[tf.Tensor]], tf.Tensor]:
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
                use_skip_connections=True),
            Concatenate())       
