"""Summary

Attributes:
    ACCOUNT_FUNDS (tf.Tensor): Description
    MATCHES (tf.Tensor): Description
    ORDER_BOOK (tf.Tensor): Description
    ORDERS (tf.Tensor): Description
"""
from funcy import compose
from keras.layers import Concatenate, Dense, Input, Lambda
from keras.models import Model
from keras import backend as K

from coinbase_train import constants as c
from coinbase_train.layers import (AtrousConvolutionBlock, Attention, DenseBlock, 
                                   FullConvolutionBlock)
from coinbase_train.experiment import config

NUM_TIME_STEPS = config()['hyper_params']['num_time_steps']

ACCOUNT_FUNDS = Input( 
    batch_shape=(None, 1, 4), 
    name='account_funds')

ORDER_BOOK = Input( 
    batch_shape=(None, None, 2, 2), 
    name='order_book')

TIME_SERIES = Input( 
    batch_shape=(None, NUM_TIME_STEPS, c.NUM_CHANNELS_IN_TIME_SERIES), 
    name='matches')

def actor_output_activation(input_tensor):
    """Summary
    
    Args:
        input_tensor (tensorflow.Tensor): Description
    
    Returns:
        tensorflow.Tensor:
    """
    def softmax_and_unpack(*input_tensors):
        _input_tensors = [K.expand_dims(t, axis=-1) for t in input_tensors]
        _soft_max = K.softmax(K.concatenate(_input_tensors))
        _output_tensors = K.tf.unstack(_soft_max, axis=-1)

        return [K.expand_dims(t, axis=-1) for t in _output_tensors]

    transaction_buy, transaction_sell = softmax_and_unpack(input_tensor[:, 0], input_tensor[:, 8])
    
    transactions_max = K.expand_dims(K.relu(input_tensor[:, 1]), axis=-1)
    transaction_percent_funds_mean = K.expand_dims(K.sigmoid(input_tensor[:, 2]), axis=-1)
    transaction_post_only = K.expand_dims(K.sigmoid(input_tensor[:, 3]), axis=-1)
    transaction_price_mean = K.expand_dims(K.sigmoid(input_tensor[:, 4]), axis=-1)
    transaction_price_sigma_cholesky_00 = K.expand_dims(K.sigmoid(input_tensor[:, 5]), axis=-1)
    transaction_price_sigma_cholesky_10 = K.expand_dims(K.sigmoid(input_tensor[:, 6]), axis=-1)
    transaction_price_sigma_cholesky_11 = K.expand_dims(K.sigmoid(input_tensor[:, 7]), axis=-1)

    return K.concatenate([transaction_buy,
                          transactions_max,
                          transaction_percent_funds_mean,
                          transaction_post_only,
                          transaction_price_mean,
                          transaction_price_sigma_cholesky_00,
                          transaction_price_sigma_cholesky_10,
                          transaction_price_sigma_cholesky_11,
                          transaction_sell])

def build_actor(
        attention_dim,
        depth,
        num_filters,
        num_stacks):
    """Summary
    
    Args:
        attention_dim (int): Description
        depth (int): Description
        num_filters (int): Description
        num_stacks (int): Description
    
    Returns:
        Model: Description
    """
    account_funds_branch = compose(
        DenseBlock(
            depth=depth, 
            units=num_filters),
        Lambda(lambda input_tensor: K.squeeze(input_tensor, axis=1)))(ACCOUNT_FUNDS)

    order_book_branch = compose(
        Attention(attention_dim),
        Lambda(lambda input_tensor: K.sum(input_tensor, axis=[-2])),
        FullConvolutionBlock(
            depth=depth, 
            kernel_size=(4, 2), 
            nb_filters=num_filters, 
            padding='same', 
            time_distributed=False)
        )(ORDER_BOOK)

    time_series_branch = compose(
        Attention(attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=depth, 
            nb_filters=num_filters, 
            nb_stacks=num_stacks, 
            time_distributed=False, 
            use_skip_connections=True)
        )(TIME_SERIES)

    merged_output_branch = Concatenate(axis=-1)([account_funds_branch,
                                                 order_book_branch,
                                                 time_series_branch]) 

    output = compose(Lambda(actor_output_activation), 
                     Dense(c.NUM_ACTIONS),
                     DenseBlock(depth=depth, 
                                units=num_filters)
                     )(merged_output_branch)

    actor = Model(
        inputs=[ACCOUNT_FUNDS, ORDER_BOOK, TIME_SERIES],
        outputs=[output]
        )

    return actor

def build_critic(
        attention_dim,
        depth,
        num_filters,
        num_stacks):
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
        batch_shape=(None, c.NUM_ACTIONS),
        name='critic_action_input')

    account_funds_branch = compose(
        DenseBlock(
            depth=depth, 
            units=num_filters),
        Concatenate(axis=-1),
        lambda tensor: [action_input] + [tensor],
        Lambda(lambda input_tensor: K.squeeze(input_tensor, axis=1))
        )(ACCOUNT_FUNDS)

    order_book_branch = compose(
        Attention(attention_dim),
        Lambda(lambda input_tensor: K.sum(input_tensor, axis=[-2])),
        FullConvolutionBlock(
            depth=depth, 
            kernel_size=(4, 2), 
            nb_filters=num_filters, 
            padding='same', 
            time_distributed=False)
        )(ORDER_BOOK)

    time_series_branch = compose(
        Attention(attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=depth, 
            nb_filters=num_filters, 
            nb_stacks=num_stacks, 
            time_distributed=False, 
            use_skip_connections=True)
        )(TIME_SERIES)

    merged_output_branch = Concatenate(axis=-1)([account_funds_branch,
                                                 order_book_branch,
                                                 time_series_branch]) 

    output = compose(
        Dense(1),
        DenseBlock(depth=depth, 
                   units=num_filters)
        )(merged_output_branch)

    critic = Model(
        inputs=[action_input, ACCOUNT_FUNDS, ORDER_BOOK, TIME_SERIES],
        outputs=[output]
        )

    return critic
