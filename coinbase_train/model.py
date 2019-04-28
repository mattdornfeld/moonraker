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

ACCOUNT_FUNDS = Input( 
    batch_shape=(None, 1, 4), 
    name='account_funds')

ACCOUNT_ORDERS = Input( 
    batch_shape=(None, None, CoinbaseOrder.get_array_length()), 
    name='account_orders')

MATCHES = Input( 
    batch_shape=(None, c.NUM_TIME_STEPS, None, CoinbaseMatch.get_array_length()), 
    name='matches')

ORDER_BOOK = Input( 
    batch_shape=(None, c.NUM_TIME_STEPS, None, 2, 2), 
    name='order_book')

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

    cancel_buy, cancel_none, cancel_sell = softmax_and_unpack(input_tensor[:, 0], 
                                                              input_tensor[:, 3], 
                                                              input_tensor[:, 4])

    cancel_max_price = K.expand_dims(K.relu(input_tensor[:, 1]), axis=-1)
    cancel_min_price = K.expand_dims(K.relu(input_tensor[:, 2]), axis=-1)

    transaction_buy, transaction_none, transaction_sell = softmax_and_unpack(input_tensor[:, 5], 
                                                                             input_tensor[:, 6], 
                                                                             input_tensor[:, 11])
    
    max_transactions = K.expand_dims(K.relu(input_tensor[:, 7]), axis=-1)
    transaction_percent_funds_mean = K.expand_dims(K.sigmoid(input_tensor[:, 8]), axis=-1)
    transaction_post_only = K.expand_dims(K.sigmoid(input_tensor[:, 9]), axis=-1)
    transaction_price_mean = K.expand_dims(K.relu(input_tensor[:, 10]), axis=-1)
    transaction_price_sigma_cholesky_00 = K.expand_dims(K.relu(input_tensor[:, 12]), axis=-1)
    transaction_price_sigma_cholesky_10 = K.expand_dims(input_tensor[:, 13], axis=-1)
    transaction_price_sigma_cholesky_11 = K.expand_dims(K.relu(input_tensor[:, 14]), axis=-1)

    return K.concatenate([cancel_buy,
                          cancel_max_price,
                          cancel_min_price,
                          cancel_none,
                          cancel_sell,
                          transaction_buy,
                          transaction_none,
                          max_transactions,
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

    order_book_branch = compose(
        Attention(attention_dim),
        Lambda(lambda input_tensor: K.sum(input_tensor, axis=[-2])),
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

    order_book_branch = compose(
        Attention(attention_dim),
        Lambda(lambda input_tensor: K.sum(input_tensor, axis=[-2])),
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
