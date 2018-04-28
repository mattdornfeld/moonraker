from keras.layers import Input, Dense, LSTM, GRU, Bidirectional, Concatenate, BatchNormalization
from keras.models import Model
from keras import backend as K

from gdax_train.layers import Attention
from gdax_train.wrappers import TimeDistributed
from gdax_train.constants import *

gdax_events_input = Input( 
	batch_shape = GDAX_BATCH_SHAPE, 
	name = 'critic_gdax_input')

wallet_events_input = Input( 
	batch_shape = WALLET_BATCH_SHAPE,
	name = 'critic_wallet_input')

def create_bidirectional_gru_cell(input_tensor, hidden_dim, batch_normalization=True):

	if batch_normalization:
		normalized_tensor = BatchNormalization() (input_tensor)
	else:
		normalized_tensor = input_tensor

	if len(input_tensor.shape) > 3:
		h = TimeDistributed(
			Bidirectional(
			GRU(
			units = hidden_dim,
			return_sequences = True,
			go_backwards = True) ) ) (normalized_tensor)
	else:
		h =	Bidirectional(
			GRU(
			units = hidden_dim,
			return_sequences = True,
			go_backwards = True) )  (normalized_tensor)

	return h

def create_dense_cell(input_tensor, hidden_dim, batch_normalization=True):

	if batch_normalization:
		normalized_tensor = BatchNormalization() (input_tensor)
	else:
		normalized_tensor = input_tensor

	h = Dense(hidden_dim) (normalized_tensor)

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
	# 	batch_shape = GDAX_BATCH_SHAPE, 
	# 	name = 'actor_gdax_input')

	# wallet_events_input = Input( 
	# 	batch_shape = WALLET_BATCH_SHAPE,
	# 	name = 'actor_wallet_input')

	#gdax events branch
	input_tensor = gdax_events_input
	for _ in range(num_cells_gdax_branch):

		h_gdax = create_bidirectional_gru_cell(input_tensor, 
			hidden_dim_gdax_branch)

		input_tensor = h_gdax

	attended_state_gdax = create_attention_cell(
		h_gdax, attention_dim_level_1)

	#wallet events branch
	input_tensor = wallet_events_input
	for _ in range(num_cells_wallet_branch):

		h_wallet = create_bidirectional_gru_cell(input_tensor, 
			hidden_dim_wallet_branch)

		input_tensor = h_wallet

	attended_state_wallet = create_attention_cell(
		h_wallet, attention_dim_level_1)

	#merge branch
	merged_hidden_state = Concatenate(axis=-1)([attended_state_gdax, attended_state_wallet])

	input_tensor = merged_hidden_state
	for _ in range(num_cells_merge_branch):

		h = create_bidirectional_gru_cell(input_tensor,
		 hidden_dim_merge_branch)

		input_tensor = h

	attended_state_merge_branch = create_attention_cell(
		h, attention_dim_merge_branch)

	action = create_dense_cell(attended_state_merge_branch, NUM_ACTIONS)

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
	# 	batch_shape = GDAX_BATCH_SHAPE, 
	# 	name = 'critic_gdax_input')

	# wallet_events_input = Input( 
	# 	batch_shape = WALLET_BATCH_SHAPE,
	# 	name = 'critic_wallet_input')

	action_input = Input( 
		batch_shape = (None, NUM_ACTIONS),
		name = 'critic_action_input' )
	# action_input = Input(tensor = actor.outputs[0])

	#gdax events branch
	input_tensor = gdax_events_input
	for _ in range(num_cells_gdax_branch):

		h_gdax = create_bidirectional_gru_cell(input_tensor, 
			hidden_dim_gdax_branch)

		input_tensor = h_gdax

	attended_state_gdax = create_attention_cell(
		h_gdax, attention_dim_level_1)

	#wallet events branch
	input_tensor = wallet_events_input
	for _ in range(num_cells_wallet_branch):

		h_wallet = create_bidirectional_gru_cell(input_tensor, 
			hidden_dim_wallet_branch)

		input_tensor = h_wallet

	attended_state_wallet = create_attention_cell(
		h_wallet, attention_dim_level_1)

	#merge branch
	merged_hidden_state = Concatenate(axis=-1)([attended_state_gdax, attended_state_wallet])

	input_tensor = merged_hidden_state
	for _ in range(num_cells_merge_branch):
		h = create_bidirectional_gru_cell(input_tensor,
		 hidden_dim_merge_branch)
		input_tensor = h

	attended_state_merge_branch = create_attention_cell(
		h, attention_dim_merge_branch)

	merged_hidden_state = Concatenate(axis=-1)([action_input, 
		attended_state_merge_branch])

	input_tensor = merged_hidden_state
	for _ in range(num_cells_dense_merge_branch):
		h = create_dense_cell(input_tensor, hidden_dim_dense_merge_branch)
		input_tensor = h

	action_value = create_dense_cell(h, 1)

	critic = Model(
		inputs = [action_input, gdax_events_input, wallet_events_input],
		outputs = [action_value])

	return critic


if __name__ == '__main__':
	actor = build_actor(
		hidden_dim_gdax_branch=100,
		hidden_dim_wallet_branch=100,
		hidden_dim_merge_branch=100,
		attention_dim_level_1=100,
		attention_dim_merge_branch=100,
		num_cells_gdax_branch=3,
		num_cells_wallet_branch=3 ,
		num_cells_merge_branch=3) 

	critic = build_critic(
		hidden_dim_gdax_branch = 100,
		hidden_dim_wallet_branch = 100,
		hidden_dim_merge_branch = 100,
		hidden_dim_dense_merge_branch = 100,
		attention_dim_level_1 = 100,
		attention_dim_merge_branch = 100,
		num_cells_gdax_branch = 3,
		num_cells_wallet_branch = 3,
		num_cells_merge_branch = 3,
		num_cells_dense_merge_branch = 3)

	actor.compile(optimizer='sgd', loss='mse')
	critic.compile(optimizer='sgd', loss='mse')

	loss = K.mean(critic.outputs[0])
	combined_inputs = critic.inputs[:2] + [Input(tensor=actor.outputs[0])]
	combined_output = critic(combined_inputs)

	loss = K.mean(combined_output)

	# loss = K.mean(combined_output)
	# grads = K.gradients(loss, actor.trainable_weights)