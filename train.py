from collections import namedtuple
from datetime import datetime, timedelta
from keras.optimizers import Adam, SGD
from rl.agents import DDPGAgent
from rl.memory import SequentialMemory
from rl.random import OrnsteinUhlenbeckProcess
from rl.core import Processor

from gdax_train.constants import *
from gdax_train.models.hierarchical_gru import build_actor, build_critic
from gdax_train.environment import MockExchange, Wallet
from gdax_train.layers import Attention
from gdax_train.wrappers import TimeDistributed

HyperParams = namedtuple('HyperParams', [
        'actor_hidden_dim_gdax_branch',
        'actor_hidden_dim_wallet_branch',
        'actor_hidden_dim_merge_branch',
        'actor_attention_dim_level_1',
        'actor_attention_dim_merge_branch',
        'actor_num_cells_gdax_branch',
        'actor_num_cells_wallet_branch',
        'actor_num_cells_merge_branch',   
        'critic_hidden_dim_gdax_branch',
        'critic_hidden_dim_wallet_branch',
        'critic_hidden_dim_merge_branch',
        'critic_hidden_dim_dense_merge_branch',
        'critic_attention_dim_level_1',
        'critic_attention_dim_merge_branch',
        'critic_num_cells_gdax_branch',
        'critic_num_cells_wallet_branch',
        'critic_num_cells_merge_branch',
        'critic_num_cells_dense_merge_branch',
        'ddpg_batch_size',
        'ddpg_theta',
        'ddpg_mu',
        'ddpg_sigma',
        'ddpg_gamma',
        'ddpg_target_model_update'])

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

def build_and_train(hyper_params, start_dt, end_dt, time_delta):

    actor = build_actor(
        hidden_dim_gdax_branch=hyper_params.actor_hidden_dim_gdax_branch,
        hidden_dim_wallet_branch=hyper_params.actor_hidden_dim_wallet_branch,
        hidden_dim_merge_branch=hyper_params.actor_hidden_dim_merge_branch,
        attention_dim_level_1=hyper_params.actor_attention_dim_level_1,
        attention_dim_merge_branch=hyper_params.actor_attention_dim_merge_branch,
        num_cells_gdax_branch=hyper_params.actor_num_cells_gdax_branch,
        num_cells_wallet_branch=hyper_params.actor_num_cells_wallet_branch,
        num_cells_merge_branch=hyper_params.actor_num_cells_merge_branch
        ) 

    critic = build_critic(  
        hidden_dim_gdax_branch=hyper_params.critic_hidden_dim_gdax_branch,
        hidden_dim_wallet_branch=hyper_params.critic_hidden_dim_wallet_branch,
        hidden_dim_merge_branch=hyper_params.critic_hidden_dim_merge_branch,
        hidden_dim_dense_merge_branch=hyper_params.critic_hidden_dim_dense_merge_branch,
        attention_dim_level_1=hyper_params.critic_attention_dim_level_1,
        attention_dim_merge_branch=hyper_params.critic_attention_dim_merge_branch,
        num_cells_gdax_branch=hyper_params.critic_num_cells_gdax_branch,
        num_cells_wallet_branch=hyper_params.critic_num_cells_wallet_branch,
        num_cells_merge_branch=hyper_params.critic_num_cells_merge_branch,
        num_cells_dense_merge_branch=hyper_params.critic_num_cells_dense_merge_branch)

    env = MockExchange( 
        wallet=Wallet(initital_product_amount=INITIAL_PRODUCT_AMOUNT, initial_usd=INITIAL_USD),
        start_dt=start_dt,
        end_dt=end_dt,
        time_delta=time_delta,
        sequence_length=NUM_TIME_STEPS,
        num_workers=NUM_WORKERS,
        buffer_size=ENV_BUFFER_SIZE)

    agent = create_agent(
        actor=actor,
        critic=critic,
        batch_size=hyper_params.ddpg_batch_size,
        buffer_size=DDPG_BUFFER_SIZE,
        window_length=DDPG_WINDOW_LENGTH,
        theta=hyper_params.ddpg_theta,
        mu=hyper_params.ddpg_mu,
        sigma=hyper_params.ddpg_sigma,
        nb_steps_warmup_critic=NUM_STEPS_WARMUP_CRITIC,
        nb_steps_warmup_actor=NUM_STEPS_WARMUP_ACTOR,
        gamma=hyper_params.ddpg_gamma,
        target_model_update=hyper_params.ddpg_target_model_update,
        custom_model_objects = {
        'Attention' : Attention, 
        'TimeDistributed' : TimeDistributed}
        )

    agent.fit(env=env, nb_steps=10, log_interval=10)

    return agent


if __name__ == '__main__':
    hyper_params = HyperParams(
        actor_hidden_dim_gdax_branch=100,
        actor_hidden_dim_wallet_branch=100,
        actor_hidden_dim_merge_branch=100,
        actor_attention_dim_level_1=100,
        actor_attention_dim_merge_branch=100,
        actor_num_cells_gdax_branch=3,
        actor_num_cells_wallet_branch=3 ,
        actor_num_cells_merge_branch=3,        
        critic_hidden_dim_gdax_branch = 100,
        critic_hidden_dim_wallet_branch = 100,
        critic_hidden_dim_merge_branch = 100,
        critic_hidden_dim_dense_merge_branch = 100,
        critic_attention_dim_level_1 = 100,
        critic_attention_dim_merge_branch = 100,
        critic_num_cells_gdax_branch = 3,
        critic_num_cells_wallet_branch = 3,
        critic_num_cells_merge_branch = 3,
        critic_num_cells_dense_merge_branch = 3,
        ddpg_batch_size=5,
        ddpg_theta=0.15,
        ddpg_mu=0,
        ddpg_sigma=0.3,
        ddpg_gamma=0.99,
        ddpg_target_model_update=1e-3,
        )

    agent = build_and_train(
        hyper_params=hyper_params, 
        start_dt=datetime.now(), 
        end_dt=datetime.now()+1000*timedelta(seconds=600),
        time_delta=timedelta(seconds=600))
