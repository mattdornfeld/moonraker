from datetime import datetime, timedelta
from keras.optimizers import Adam, SGD
from rl.agents import DDPGAgent
from rl.memory import SequentialMemory
from rl.random import OrnsteinUhlenbeckProcess
from rl.core import Processor

from bitcoin_trader.constants import *
from bitcoin_trader.train.model import build_actor, build_critic
from bitcoin_trader.train.environment import MockExchange, Wallet
from bitcoin_trader.train.layers import Attention
from bitcoin_trader.train.wrappers import TimeDistributed

class MultiInputProcessor(Processor):
    def __init__(self):
        pass

    def _calc_largest_number_of_events(self, state_batch):
        num_branches = len(state_batch[0][0])
        most_events = [0 for _ in range(num_branches)]
        for state in state_batch:
            for i, branch in enumerate(state[0]):
                num_events = branch.shape[1]
                if num_events > most_events[i]:
                    most_events[i] = num_events

        return most_events

    def _pad_state(self, branch_state, most_event):
        num_event = branch_state.shape[1]
        
        if num_event < most_event:
            padding = np.zeros(
                (branch_state.shape[0], 
                 most_event - num_event, 
                 branch_state.shape[2]) )

            branch_state = np.append(branch_state, padding, axis=1)

        return branch_state


    def process_state_batch(self, state_batch):
        most_events = self._calc_largest_number_of_events(state_batch)

        branched_state_batches = [[] for _ in range(len(most_events))]
        for state in state_batch:
            for i, branch_state in enumerate(state[0]):
                branch_state = self._pad_state(branch_state, most_events[i])
                branch_state = np.expand_dims(branch_state, axis=0)
                branched_state_batches[i].append(branch_state)

        branched_state_batches = list(map(np.vstack, branched_state_batches))

        return branched_state_batches

def create_agent(actor, critic, batch_size, buffer_size, window_length, theta, mu, 
    sigma, nb_steps_warmup_critic, nb_steps_warmup_actor, gamma, 
    target_model_update, custom_model_objects):

    memory = SequentialMemory(limit=DDPG_BUFFER_SIZE, 
        window_length=DDPG_WINDOW_LENGTH)
    
    random_process = OrnsteinUhlenbeckProcess(size=NUM_ACTIONS, 
        theta=theta, mu=mu, sigma=sigma)

    processor = MultiInputProcessor()
    
    critic_action_input = critic.inputs[0]
    
    agent = DDPGAgent(
        nb_actions = NUM_ACTIONS, 
        batch_size = batch_size,
        actor = actor, 
        critic = critic, 
        critic_action_input = critic_action_input, 
        processor = processor,
        memory = memory, 
        nb_steps_warmup_critic = nb_steps_warmup_critic,
        nb_steps_warmup_actor = nb_steps_warmup_actor,
        random_process = random_process, 
        gamma = gamma, 
        target_model_update = target_model_update,
        custom_model_objects = custom_model_objects)

    agent.compile('sgd')

    return agent


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

    env = MockExchange( 
        wallet = Wallet(initital_product_amount = 0, initial_usd = 1000),
        start_dt = datetime.now(),
        end_dt = datetime.now() + 1000*timedelta(seconds=600),
        time_delta = timedelta(seconds=600),
        sequence_length = 3,
        num_workers = 3,
        buffer_size = 100)

    agent = create_agent(
        actor = actor,
        critic = critic,
        batch_size = BATCH_SIZE,
        buffer_size = DDPG_BUFFER_SIZE,
        window_length = DDPG_WINDOW_LENGTH,
        theta = 0.15,
        mu = 0,
        sigma = 0.3,
        nb_steps_warmup_critic = 5,
        nb_steps_warmup_actor = 5,
        gamma = 0.99,
        target_model_update = 1e-3,
        custom_model_objects = {
        'Attention' : Attention, 
        'TimeDistributed' : TimeDistributed}
        )

    agent.fit(env = env, nb_steps = 10, log_interval = 10)
