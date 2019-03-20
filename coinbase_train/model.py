"""Summary

Attributes:
    ACCOUNT_FUNDS (tf.Tensor): Description
    MATCHES (tf.Tensor): Description
    ORDER_BOOK (tf.Tensor): Description
    ORDERS (tf.Tensor): Description
"""
from funcy import compose
from keras.layers import BatchNormalization, Concatenate, Dense, Input, Lambda
from keras.models import Model
from keras import backend as K

from fakebase.orm import CoinbaseMatch, CoinbaseOrder

from coinbase_train import constants as c
from coinbase_train.layers import (AtrousConvolutionBlock, Attention, DenseBlock, 
                                   FullConvolutionBlock)

MATCHES = Input( 
    batch_shape=(c.BATCH_SIZE, c.NUM_TIME_STEPS, None, CoinbaseMatch.get_array_length()), 
    name='matches')

ORDER_BOOK = Input( 
    batch_shape=(c.BATCH_SIZE, c.NUM_TIME_STEPS, None, 2, 2), 
    name='order_book')

ACCOUNT_ORDERS = Input( 
    batch_shape=(c.BATCH_SIZE, None, CoinbaseOrder.get_array_length()), 
    name='account_orders')

ACCOUNT_FUNDS = Input( 
    batch_shape=(c.BATCH_SIZE, 1, 4), 
    name='account_funds')

def actor_output_activation(input_tensor):
    """Summary
    
    Args:
        input_tensor (tensorflow.Tensor): Description
    
    Returns:
        tensorflow.Tensor: Description
    """
    size = K.expand_dims(input_tensor[:, 0], axis=-1)
    price = K.expand_dims(K.relu(input_tensor[:, 1]), axis=-1)
    post_only = K.expand_dims(K.sigmoid(input_tensor[:, 2]), axis=-1)
    do_nothing = K.expand_dims(K.sigmoid(input_tensor[:, 3]), axis=-1)
    cancel_all_orders = K.expand_dims(K.sigmoid(input_tensor[:, 4]), axis=-1)

    return K.concatenate([size, price, post_only, do_nothing, cancel_all_orders])

def build_actor(
        attention_dim,
        batch_size,
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
    match_branch = compose(
        Attention(attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=depth, 
            nb_filters=num_filters, 
            nb_stacks=num_stacks, 
            time_distributed=True, 
            use_skip_connections=True),
        BatchNormalization()
        )(MATCHES)

    new_shape = (batch_size, c.NUM_TIME_STEPS, -1, 2 * num_filters)
    order_book_branch = compose(
        Attention(attention_dim),
        Lambda(lambda input_tensor: 
               K.reshape(input_tensor, new_shape)),
        FullConvolutionBlock(
            depth=depth, 
            kernel_size=(4, 2), 
            nb_filters=num_filters, 
            padding='same', 
            time_distributed=True),
        BatchNormalization()
        )(ORDER_BOOK)

    merged_branch = Concatenate(axis=-1)([match_branch, order_book_branch])

    merged_branch_output = compose(
        Attention(attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=depth, 
            nb_filters=num_filters, 
            nb_stacks=num_stacks, 
            time_distributed=False, 
            use_skip_connections=True),
        BatchNormalization()
        )(merged_branch)

    account_orders_branch = compose(
        Attention(attention_dim),
        DenseBlock(
            depth=depth, 
            units=num_filters),
        BatchNormalization())(ACCOUNT_ORDERS)

    account_funds_branch = compose(
        DenseBlock(
            depth=depth, 
            units=num_filters),
        BatchNormalization(),
        Lambda(lambda input_tensor: K.squeeze(input_tensor, axis=1)))(ACCOUNT_FUNDS)

    merged_output_branch = Concatenate(axis=-1)([merged_branch_output, 
                                                 account_orders_branch, 
                                                 account_funds_branch])

    output = compose(Lambda(actor_output_activation), 
                     Dense(c.NUM_ACTIONS))(merged_output_branch)

    actor = Model(
        inputs=[MATCHES, ORDER_BOOK, ACCOUNT_ORDERS, ACCOUNT_FUNDS],
        outputs=[output]
        )

    return actor

def build_critic(
        attention_dim,
        batch_size,
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
        batch_shape=(batch_size, c.NUM_ACTIONS),
        name='critic_action_input')

    match_branch = compose(
        Attention(attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=depth, 
            nb_filters=num_filters, 
            nb_stacks=num_stacks, 
            time_distributed=True, 
            use_skip_connections=True),
        BatchNormalization()
        )(MATCHES)

    new_shape = (batch_size, c.NUM_TIME_STEPS, -1, 2 * num_filters)
    order_book_branch = compose(
        Attention(attention_dim),
        Lambda(lambda input_tensor: 
               K.reshape(input_tensor, new_shape)),
        FullConvolutionBlock(
            depth=depth, 
            kernel_size=(4, 2), 
            nb_filters=num_filters, 
            padding='same', 
            time_distributed=True),
        BatchNormalization()
        )(ORDER_BOOK)

    merged_branch = Concatenate(axis=-1)([match_branch, order_book_branch])

    merged_branch_output = compose(
        Attention(attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=depth, 
            nb_filters=num_filters, 
            nb_stacks=num_stacks, 
            time_distributed=False, 
            use_skip_connections=True),
        BatchNormalization()
        )(merged_branch)

    account_orders_branch = compose(
        Attention(attention_dim),
        DenseBlock(
            depth=depth, 
            units=num_filters),
        BatchNormalization())(ACCOUNT_ORDERS)

    action_funds_branch = compose(
        DenseBlock(
            depth=depth, 
            units=num_filters),
        BatchNormalization(),
        Concatenate(axis=-1),
        lambda tensor: [action_input] + [tensor],
        Lambda(lambda input_tensor: K.squeeze(input_tensor, axis=1))
        )(ACCOUNT_FUNDS)

    merged_output_branch = Concatenate(axis=-1)([merged_branch_output, 
                                                 account_orders_branch, 
                                                 action_funds_branch])

    output = Dense(1)(merged_output_branch)

    critic = Model(
        inputs=[action_input, MATCHES, ORDER_BOOK, ACCOUNT_ORDERS, ACCOUNT_FUNDS],
        outputs=[output]
        )

    return critic
