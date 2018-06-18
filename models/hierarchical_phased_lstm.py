from keras.layers import Input, Dense, Bidirectional, Concatenate, BatchNormalization
from keras.models import Model
from keras import backend as K
from phased_lstm_keras.PhasedLSTM import PhasedLSTM

from gdax_train.layers import Attention
from gdax_train.wrappers import TimeDistributed
from gdax_train.constants import *

gdax_events_input = Input( 
    batch_shape = GDAX_BATCH_SHAPE, 
    name = 'critic_gdax_input')

wallet_events_input = Input( 
    batch_shape = WALLET_BATCH_SHAPE,
    name = 'critic_wallet_input')

def create_bidirectional_plstm_cell(input_tensor, hidden_dim, batch_normalization=True):

    if batch_normalization:
        normalized_tensor = BatchNormalization() (input_tensor)
    else:
        normalized_tensor = input_tensor

    if len(input_tensor.shape) > 3:
        h = TimeDistributed(
            Bidirectional(
            PhasedLSTM(
            units = hidden_dim,
            return_sequences = True,
            go_backwards = True) ) ) (normalized_tensor)
    else:
        h = Bidirectional(
            PhasedLSTM(
            units = hidden_dim,
            return_sequences = True,
            go_backwards = True) )  (normalized_tensor)

    return h

def create_dense_cell(input_tensor, hidden_dim, activation, batch_normalization=True):

    if batch_normalization:
        normalized_tensor = BatchNormalization() (input_tensor)
    else:
        normalized_tensor = input_tensor

    h = Dense(hidden_dim, activation=activation) (normalized_tensor)

    return h

def create_attention_cell(input_tensor, attention_dim, batch_normalization=True):

    if batch_normalization:
        normalized_tensor = BatchNormalization() (input_tensor)
    else:
        normalized_tensor = input_tensor

    h = Attention(attention_dim) (normalized_tensor)

    return h

def build_actor(
    hidden_dim_gdax_branch,
    hidden_dim_wallet_branch,
    hidden_dim_merge_branch,
    attention_dim_level_1,
    attention_dim_merge_branch,
    num_cells_gdax_branch,
    num_cells_wallet_branch,
    num_cells_merge_branch):

    # gdax_events_input = Input( 
    #   batch_shape = GDAX_BATCH_SHAPE, 
    #   name = 'actor_gdax_input')

    # wallet_events_input = Input( 
    #   batch_shape = WALLET_BATCH_SHAPE,
    #   name = 'actor_wallet_input')

    #gdax events branch
    input_tensor = gdax_events_input
    for _ in range(num_cells_gdax_branch):

        h_gdax = create_bidirectional_plstm_cell(input_tensor, 
            hidden_dim_gdax_branch, batch_normalization=True)

        input_tensor = h_gdax

    attended_state_gdax = create_attention_cell(
        h_gdax, attention_dim_level_1, batch_normalization=False)

    #wallet events branch
    input_tensor = wallet_events_input
    for _ in range(num_cells_wallet_branch):

        h_wallet = create_bidirectional_plstm_cell(input_tensor, 
            hidden_dim_wallet_branch, batch_normalization=True)

        input_tensor = h_wallet

    attended_state_wallet = create_attention_cell(
        h_wallet, attention_dim_level_1, batch_normalization=False)

    #merge branch
    merged_hidden_state = Concatenate(axis=-1)([attended_state_gdax, attended_state_wallet])

    input_tensor = merged_hidden_state
    for _ in range(num_cells_merge_branch):

        h = create_bidirectional_plstm_cell(input_tensor,
         hidden_dim_merge_branch, batch_normalization=True)

        input_tensor = h

    attended_state_merge_branch = create_attention_cell(
        h, attention_dim_merge_branch, batch_normalization=False)

    # action = create_dense_cell(attended_state_merge_branch, NUM_ACTIONS, activation='tanh', batch_normalization=True)
    action = create_dense_cell(attended_state_merge_branch, 1, activation='tanh', batch_normalization=True)

    actor = Model(
        inputs = [gdax_events_input, wallet_events_input],
        outputs = [action])

    return actor

def build_critic(
    hidden_dim_gdax_branch,
    hidden_dim_wallet_branch,
    hidden_dim_merge_branch,
    hidden_dim_dense_merge_branch,
    attention_dim_level_1,
    attention_dim_merge_branch,
    num_cells_gdax_branch,
    num_cells_wallet_branch,
    num_cells_merge_branch,
    num_cells_dense_merge_branch):

    # gdax_events_input = Input( 
    #   batch_shape = GDAX_BATCH_SHAPE, 
    #   name = 'critic_gdax_input')

    # wallet_events_input = Input( 
    #   batch_shape = WALLET_BATCH_SHAPE,
    #   name = 'critic_wallet_input')

    action_input = Input( 
        batch_shape = (None, NUM_ACTIONS),
        name = 'critic_action_input' )
    # action_input = Input(tensor = actor.outputs[0])

    #gdax events branch
    input_tensor = gdax_events_input
    for _ in range(num_cells_gdax_branch):

        h_gdax = create_bidirectional_plstm_cell(input_tensor, 
            hidden_dim_gdax_branch, batch_normalization=True)

        input_tensor = h_gdax

    attended_state_gdax = create_attention_cell(
        h_gdax, attention_dim_level_1, batch_normalization=False)

    #wallet events branch
    input_tensor = wallet_events_input
    for _ in range(num_cells_wallet_branch):

        h_wallet = create_bidirectional_plstm_cell(input_tensor, 
            hidden_dim_wallet_branch, batch_normalization=True)

        input_tensor = h_wallet

    attended_state_wallet = create_attention_cell(
        h_wallet, attention_dim_level_1, batch_normalization=False)

    #merge branch
    merged_hidden_state = Concatenate(axis=-1)([attended_state_gdax, attended_state_wallet])

    input_tensor = merged_hidden_state
    for _ in range(num_cells_merge_branch):
        h = create_bidirectional_plstm_cell(input_tensor,
         hidden_dim_merge_branch, batch_normalization=True)
        input_tensor = h

    attended_state_merge_branch = create_attention_cell(
        h, attention_dim_merge_branch, batch_normalization=False)

    # merged_hidden_state = Concatenate(axis=-1)([action_input, 
    #     attended_state_merge_branch])

    # input_tensor = merged_hidden_state
    # for _ in range(num_cells_dense_merge_branch):
    #     h = create_dense_cell(input_tensor, hidden_dim_dense_merge_branch, activation='tanh', batch_normalization=True)
    #     input_tensor = h
    h = attended_state_merge_branch
    action_value = create_dense_cell(h, 1, activation='tanh', batch_normalization=True)

    critic = Model(
        inputs = [action_input, gdax_events_input, wallet_events_input],
        outputs = [action_value])

    return critic

def build_critic(
    hidden_dim_gdax_branch,
    hidden_dim_wallet_branch,
    hidden_dim_merge_branch,
    hidden_dim_dense_merge_branch,
    attention_dim_level_1,
    attention_dim_merge_branch,
    num_cells_gdax_branch,
    num_cells_wallet_branch,
    num_cells_merge_branch,
    num_cells_dense_merge_branch):

    # gdax_events_input = Input( 
    #   batch_shape = GDAX_BATCH_SHAPE, 
    #   name = 'critic_gdax_input')

    # wallet_events_input = Input( 
    #   batch_shape = WALLET_BATCH_SHAPE,
    #   name = 'critic_wallet_input')

    action_input = Input( 
        batch_shape = (None, NUM_ACTIONS),
        name = 'critic_action_input' )
    # action_input = Input(tensor = actor.outputs[0])

    #gdax events branch
    input_tensor = gdax_events_input
    for _ in range(num_cells_gdax_branch):

        h_gdax = create_bidirectional_plstm_cell(input_tensor, 
            hidden_dim_gdax_branch, batch_normalization=True)

        input_tensor = h_gdax

    attended_state_gdax = create_attention_cell(
        h_gdax, attention_dim_level_1, batch_normalization=False)

    #wallet events branch
    input_tensor = wallet_events_input
    for _ in range(num_cells_wallet_branch):

        h_wallet = create_bidirectional_plstm_cell(input_tensor, 
            hidden_dim_wallet_branch, batch_normalization=True)

        input_tensor = h_wallet

    attended_state_wallet = create_attention_cell(
        h_wallet, attention_dim_level_1, batch_normalization=False)

    #merge branch
    merged_hidden_state = Concatenate(axis=-1)([attended_state_gdax, attended_state_wallet])

    input_tensor = merged_hidden_state
    for _ in range(num_cells_merge_branch):
        h = create_bidirectional_plstm_cell(input_tensor,
         hidden_dim_merge_branch, batch_normalization=True)
        input_tensor = h

    attended_state_merge_branch = create_attention_cell(
        h, attention_dim_merge_branch, batch_normalization=False)

    # merged_hidden_state = Concatenate(axis=-1)([action_input, 
    #     attended_state_merge_branch])

    # input_tensor = merged_hidden_state
    # for _ in range(num_cells_dense_merge_branch):
    #     h = create_dense_cell(input_tensor, hidden_dim_dense_merge_branch, activation='tanh', batch_normalization=True)
    #     input_tensor = h
    h = attended_state_merge_branch
    action_value = create_dense_cell(h, 1, activation='tanh', batch_normalization=True)

    critic = Model(
        inputs = [action_input, gdax_events_input, wallet_events_input],
        outputs = [action_value])

    return critic


if __name__ == '__main__':
    from keras.optimizers import SGD
    
    actor = build_actor(
        hidden_dim_gdax_branch=100,
        hidden_dim_wallet_branch=100,
        hidden_dim_merge_branch=100,
        attention_dim_level_1=100,
        attention_dim_merge_branch=100,
        num_cells_gdax_branch=1,
        num_cells_wallet_branch=1,
        num_cells_merge_branch=1) 

    actor.compile(optimizer=SGD(lr=0.001, clipnorm=0.1), loss='mse')

    critic = build_critic(
        hidden_dim_gdax_branch = 100,
        hidden_dim_wallet_branch = 100,
        hidden_dim_merge_branch = 100,
        hidden_dim_dense_merge_branch = 100,
        attention_dim_level_1 = 100,
        attention_dim_merge_branch = 100,
        num_cells_gdax_branch = 1,
        num_cells_wallet_branch = 1,
        num_cells_merge_branch = 1,
        num_cells_dense_merge_branch = 1)

    critic.compile(optimizer=SGD(lr=0.001, clipnorm=0.1), loss='mse')  

    from gdax_train.environment import MockExchange, Wallet
    from gdax_train.utils import MultiInputProcessor
    from datetime import datetime, timedelta

    num_episodes = 1
    num_steps_per_episode = 12

    offset = timedelta(seconds=30)
    start_dt = datetime(2018, 6, 1, 23, 59, 38, 378678)
    time_delta = timedelta(seconds=10)
    test_start_dt = start_dt
    test_end_dt = start_dt+num_steps_per_episode*time_delta
    train_start_dt = start_dt 
    train_end_dt = start_dt+num_steps_per_episode*time_delta
    time_delta = time_delta

    env = MockExchange( 
        wallet=Wallet(initital_product_amount=INITIAL_PRODUCT_AMOUNT, initial_usd=INITIAL_USD),
        start_dt=train_start_dt,
        end_dt=train_end_dt,
        time_delta=time_delta,
        sequence_length=NUM_TIME_STEPS,
        num_workers=NUM_WORKERS,
        buffer_size=ENV_BUFFER_SIZE)

    obs = [np.expand_dims(s,0) for s in env.step([1,0])[0]]
    action = np.expand_dims([1,0], axis=0)

