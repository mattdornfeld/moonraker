"""Summary

Attributes:
    ACCOUNT_FUNDS (tf.Tensor): Description
    ACCOUNT_ORDERS (tf.Tensor): Description
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
from coinbase_train.layers import (AtrousConvolutionBlock, Attention, TDConv1D, 
                                   TDConv2D, TDDense, TDMaxPooling1D)

MATCHES = Input( 
    batch_shape=(None, c.NUM_TIME_STEPS, None, CoinbaseMatch.get_array_length()), 
    name='matches')

ORDERS = Input( 
    batch_shape=(None, c.NUM_TIME_STEPS, None, CoinbaseOrder.get_array_length()), 
    name='orders')

ORDER_BOOK = Input( 
    batch_shape=(None, c.NUM_TIME_STEPS, c.PAD_ORDER_BOOK_TO_LENGTH, 2, 2), 
    name='order_book')

ACCOUNT_ORDERS = Input( 
    batch_shape=(None, c.NUM_TIME_STEPS, None, CoinbaseOrder.get_array_length()), 
    name='account_orders')

ACCOUNT_FUNDS = Input( 
    batch_shape=(None, c.NUM_TIME_STEPS, 1, 4), 
    name='account_funds')

def actor_output_activation(input_tensor):
    """Summary
    
    Args:
        input_tensor (tensorflow.Tensor): Description
    
    Returns:
        tensorflow.Tensor: Description
    """
    size = K.expand_dims(input_tensor[:, 0], axis=-1)
    price = K.expand_dims(input_tensor[:, 1], axis=-1)
    post_only = K.expand_dims(K.sigmoid(input_tensor[:, 2]), axis=-1)
    do_nothing = K.expand_dims(K.sigmoid(input_tensor[:, 3]), axis=-1)
    cancel_all_orders = K.expand_dims(K.sigmoid(input_tensor[:, 4]), axis=-1)

    return K.concatenate([size, price, post_only, do_nothing, cancel_all_orders])

def build_actor(
        account_funds_attention_dim, 
        account_funds_hidden_dim, 
        account_orders_num_filters, 
        account_orders_attention_dim,
        matches_attention_dim,
        matches_num_filters,
        merged_branch_attention_dim,
        merged_branch_num_filters,
        order_book_num_filters,
        order_book_kernel_size,
        orders_attention_dim,
        orders_num_filters):
    """Summary
    
    Args:
        account_funds_attention_dim (int): Description
        account_funds_hidden_dim (int): Description
        account_orders_num_filters (int): Description
        account_orders_attention_dim (int): Description
        matches_attention_dim (int): Description
        matches_num_filters (int): Description
        merged_branch_attention_dim (int): Description
        merged_branch_num_filters (int): Description
        order_book_num_filters (int): Description
        order_book_kernel_size (int): Description
        orders_attention_dim (int): Description
        orders_num_filters (int): Description
    
    Returns:
        Model: Description
    """
    match_branch = compose(
        Attention(matches_attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=9, 
            nb_filters=matches_num_filters, 
            nb_stacks=4, 
            time_distributed=True, 
            use_skip_connections=True),
        BatchNormalization()
        )(MATCHES)

    orders_branch = compose(
        Attention(orders_attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=9, 
            nb_filters=orders_num_filters, 
            nb_stacks=4, 
            time_distributed=True, 
            use_skip_connections=True),
        BatchNormalization()
        )(ORDERS)

    order_book_branch = compose(
        Lambda(lambda input_tensor: K.squeeze(input_tensor, axis=-1)),
        TDConv1D(1, order_book_kernel_size),
        TDMaxPooling1D(),
        TDConv1D(order_book_num_filters, order_book_kernel_size),
        TDMaxPooling1D(),
        Lambda(lambda input_tensor: K.squeeze(input_tensor, axis=-2)),
        TDConv2D(order_book_num_filters, (order_book_kernel_size, 2)),
        BatchNormalization()
        )(ORDER_BOOK)

    account_orders_branch = compose(
        Attention(account_orders_attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=9, 
            nb_filters=account_orders_num_filters, 
            nb_stacks=4, 
            time_distributed=True, 
            use_skip_connections=True),
        BatchNormalization()
        )(ACCOUNT_ORDERS)

    account_funds_branchs = compose(
        Attention(account_funds_attention_dim),
        TDDense(account_funds_hidden_dim, activation='relu'),
        BatchNormalization()
        )(ACCOUNT_FUNDS)

    merged_branch = Concatenate(axis=-1)([match_branch, 
                                          orders_branch, 
                                          order_book_branch, 
                                          account_orders_branch,
                                          account_funds_branchs])

    output = compose(
        Lambda(actor_output_activation),
        Dense(5),
        Attention(merged_branch_attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=9, 
            nb_filters=merged_branch_num_filters, 
            nb_stacks=4, 
            time_distributed=False, 
            use_skip_connections=True),
        BatchNormalization()
        )(merged_branch)

    actor = Model(
        inputs=[MATCHES, ORDERS, ORDER_BOOK, ACCOUNT_ORDERS, ACCOUNT_FUNDS],
        outputs=[output]
        )

    return actor

def build_critic(
        account_funds_attention_dim, 
        account_funds_hidden_dim,
        account_orders_num_filters, 
        account_orders_attention_dim,
        matches_attention_dim,
        matches_num_filters,
        merged_branch_attention_dim,
        merged_branch_num_filters,
        order_book_num_filters,
        order_book_kernel_size,
        orders_attention_dim,
        orders_num_filters,
        output_branch_hidden_dim):
    """Summary
    
    Args:
        account_funds_attention_dim (int): Description
        account_funds_hidden_dim (int): Description
        account_orders_num_filters (int): Description
        account_orders_attention_dim (int): Description
        matches_attention_dim (int): Description
        matches_num_filters (int): Description
        merged_branch_attention_dim (int): Description
        merged_branch_num_filters (int): Description
        order_book_num_filters (int): Description
        order_book_kernel_size (int): Description
        orders_attention_dim (int): Description
        orders_num_filters (int): Description
        output_branch_hidden_dim (int): Description
    
    Returns:
        Model: Description
    """
    action_input = Input( 
        batch_shape=(None, c.NUM_ACTIONS),
        name='critic_action_input')

    match_branch = compose(
        Attention(matches_attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=9, 
            nb_filters=matches_num_filters, 
            nb_stacks=4, 
            time_distributed=True, 
            use_skip_connections=True),
        BatchNormalization()
        )(MATCHES)

    orders_branch = compose(
        Attention(orders_attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=9, 
            nb_filters=orders_num_filters, 
            nb_stacks=4, 
            time_distributed=True, 
            use_skip_connections=True),
        BatchNormalization()
        )(ORDERS)

    order_book_branch = compose(
        Lambda(lambda input_tensor: K.squeeze(input_tensor, axis=-1)),
        TDConv1D(1, order_book_kernel_size),
        TDMaxPooling1D(),
        TDConv1D(order_book_num_filters, order_book_kernel_size),
        TDMaxPooling1D(),
        Lambda(lambda input_tensor: K.squeeze(input_tensor, axis=-2)),
        TDConv2D(order_book_num_filters, (order_book_kernel_size, 2)),
        BatchNormalization()
        )(ORDER_BOOK)

    account_orders_branch = compose(
        Attention(account_orders_attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=9, 
            nb_filters=account_orders_num_filters, 
            nb_stacks=4, 
            time_distributed=True, 
            use_skip_connections=True),
        BatchNormalization()
        )(ACCOUNT_ORDERS)

    account_funds_branchs = compose(
        Attention(account_funds_attention_dim),
        TDDense(account_funds_hidden_dim, activation='relu'),
        BatchNormalization()
        )(ACCOUNT_FUNDS)

    merged_branch = Concatenate(axis=-1)([match_branch, 
                                          orders_branch, 
                                          order_book_branch, 
                                          account_orders_branch,
                                          account_funds_branchs])

    output = compose(
        Dense(1),
        Dense(output_branch_hidden_dim),
        Concatenate(axis=-1),
        lambda tensor: [action_input] + [tensor],
        Attention(merged_branch_attention_dim),
        AtrousConvolutionBlock(
            causal=False, 
            dilation_depth=9, 
            nb_filters=merged_branch_num_filters, 
            nb_stacks=4, 
            time_distributed=False, 
            use_skip_connections=True),
        BatchNormalization()
        )(merged_branch)

    critic = Model(
        inputs=[action_input, MATCHES, ORDERS, ORDER_BOOK, ACCOUNT_ORDERS, ACCOUNT_FUNDS],
        outputs=[output]
        )

    return critic
