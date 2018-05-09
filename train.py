from collections import namedtuple
from datetime import datetime, timedelta
from keras.optimizers import Adam, SGD
from gdax_train.lib.rl.agents import DDPGAgent
from gdax_train.lib.rl.memory import SequentialMemory
from gdax_train.lib.rl.random import OrnsteinUhlenbeckProcess
from sacred import Experiment
from sacred.observers import MongoObserver

from gdax_train.callbacks import History
from gdax_train.constants import *
from gdax_train.environment import MockExchange, Wallet
from gdax_train.layers import Attention
from gdax_train.models.hierarchical_gru import build_actor, build_critic
from gdax_train.utils import MultiInputProcessor
from gdax_train.wrappers import TimeDistributed

# ex = Experiment()
# ex.observers.append(MongoObserver.create(url=MONGO_DB_URL))

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
        actor=actor, 
        batch_size=batch_size,
        critic=critic, 
        critic_action_input=critic_action_input, 
        custom_model_objects=custom_model_objects,
        gamma=gamma, 
        memory=memory, 
        nb_actions=NUM_ACTIONS, 
        nb_steps_warmup_actor=nb_steps_warmup_actor,
        nb_steps_warmup_critic=nb_steps_warmup_critic,
        processor=processor,
        random_process=random_process, 
        target_model_update=target_model_update)

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

    callbacks = [History()]

    agent.fit(
        callbacks=callbacks, 
        env=env, 
        log_interval=10,
        nb_max_episode_steps=10, 
        nb_steps=10) 

    return agent, callbacks

def evaluate_agent(agent, start_dt, end_dt, time_delta):
    
    wallet = Wallet(initital_product_amount=INITIAL_PRODUCT_AMOUNT, initial_usd=INITIAL_USD)
    
    env = MockExchange( 
        wallet=wallet,
        start_dt=start_dt,
        end_dt=end_dt,
        time_delta=time_delta,
        sequence_length=NUM_TIME_STEPS,
        num_workers=NUM_WORKERS,
        buffer_size=ENV_BUFFER_SIZE)

    callbacks = [History()]

    nb_max_episode_steps = int((end_dt - start_dt) / time_delta)

    agent.test(
        callbacks=callbacks, 
        env=env, 
        nb_max_episode_steps=nb_max_episode_steps)

    return callbacks

def main(hyper_params, train_start_dt, train_end_dt, test_start_dt, 
    test_end_dt, time_delta):
    
    agent, train_callbacks = build_and_train(hyper_params, train_start_dt, 
        train_end_dt, time_delta)

    test_callbacks = evaluate_agent(agent, test_start_dt, test_end_dt, time_delta)



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

    agent, train_callbacks = build_and_train(
        hyper_params=hyper_params, 
        start_dt=datetime.now(), 
        end_dt=datetime.now()+1000*timedelta(seconds=600),
        time_delta=timedelta(seconds=600))

    test_callbacks = evaluate_agent(
        agent=agent, 
        start_dt=datetime.now(), 
        end_dt=datetime.now()+1000*timedelta(seconds=600),
        time_delta=timedelta(seconds=600))

    from IPython import embed; embed()
