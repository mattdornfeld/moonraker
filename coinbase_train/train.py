"""Summary

Attributes:
    ex (Experiment): Description
"""
from datetime import timedelta

from dateutil import parser
from keras.optimizers import SGD
from sacred import Experiment
from sacred.observers import MongoObserver

from lib.rl.agents import DDPGAgent
from lib.rl.memory import SequentialMemory
from lib.rl.random import OrnsteinUhlenbeckProcess

from coinbase_train import constants as c
from coinbase_train import utils
from coinbase_train.callbacks import TrainLogger
from coinbase_train.environment import MockEnvironment
from coinbase_train.layers import Attention
from coinbase_train.model import build_actor, build_critic
from coinbase_train.processor import CoibaseEnvironmentProcessor

ex = Experiment()
ex.observers.append(MongoObserver.create(url=c.MONGO_DB_URL))

def create_agent(actor, critic, hyper_params):
    """Summary
    
    Args:
        actor (keras.models.Model): Description
        critic (keras.models.Model): Description
        hyper_params (utils.HyperParameters): Description
    
    Returns:
        DDPGAgent: Description

    """
    memory = SequentialMemory(
        limit=100000, 
        window_length=1)
    
    random_process = OrnsteinUhlenbeckProcess(
        size=c.NUM_ACTIONS, 
        theta=0.15, 
        mu=0.0,
        sigma=0.3)

    processor = CoibaseEnvironmentProcessor()
    
    critic_action_input = critic.inputs[0]
    
    agent = DDPGAgent(
        actor=actor, 
        batch_size=hyper_params.batch_size,
        critic=critic, 
        critic_action_input=critic_action_input, 
        custom_model_objects={'Attention' : Attention}, 
        memory=memory, 
        nb_actions=c.NUM_ACTIONS,
        nb_steps_warmup_actor=hyper_params.batch_size,
        nb_steps_warmup_critic=hyper_params.batch_size,
        processor=processor,
        random_process=random_process)

    agent.compile(SGD(lr=0.001, clipnorm=0.1))

    return agent

def build_and_train(hyper_params, tensorboard_dir, train_environment_configs):
    """Summary
    
    Args:
        hyper_params (utils.HyperParameters): Description
        tensorboard_dir (pathlib.Path): Description
        train_environment_configs (utils.EnvironmentConfigs): Description
    
    Returns:
        List[rl.callbacks.Callback]: Description
    """

    actor = build_actor(
        attention_dim=hyper_params.attention_dim,
        batch_size=hyper_params.batch_size,
        depth=hyper_params.depth,
        num_filters=hyper_params.num_filters,
        num_stacks=hyper_params.num_stacks)

    critic = build_critic(
        attention_dim=hyper_params.attention_dim,
        batch_size=hyper_params.batch_size,
        depth=hyper_params.depth,
        num_filters=hyper_params.num_filters,
        num_stacks=hyper_params.num_stacks)

    train_environment = MockEnvironment(
        end_dt=train_environment_configs.end_dt,
        initial_usd=train_environment_configs.initial_usd,
        initial_btc=train_environment_configs.initial_btc, 
        num_workers=c.NUM_DATABASE_WORKERS,
        num_time_steps=hyper_params.num_time_steps,
        start_dt=train_environment_configs.start_dt,
        time_delta=train_environment_configs.time_delta,
        verbose=True)

    agent = create_agent(
        actor=actor,
        critic=critic,
        hyper_params=hyper_params)

    nb_max_episode_steps = utils.calc_nb_max_episode_steps(
        end_dt=train_environment_configs.end_dt,
        start_dt=train_environment_configs.start_dt,
        time_delta=train_environment_configs.time_delta)

    callbacks = [TrainLogger(sacred_experiment=ex, tensorboard_dir=tensorboard_dir)]

    history = agent.fit(
        callbacks=callbacks,
        env=train_environment, 
        log_interval=1,
        nb_max_episode_steps=nb_max_episode_steps, 
        nb_steps=train_environment_configs.num_episodes * nb_max_episode_steps,
        verbose=2) 

    callbacks.append(history)

    return agent, callbacks

@ex.config
def config():
    """Configuration variables recorded in Sacred. These will be
    automatically passed to the main function.
    """
    hyper_params = dict(  #pylint: disable=W0612
        attention_dim=50,
        batch_size=c.BATCH_SIZE,
        depth=4,
        learning_rate=0.001,
        num_filters=100,
        num_stacks=1,
        num_time_steps=c.NUM_TIME_STEPS)  

    train_environment_configs = dict(  #pylint: disable=W0612
        end_dt=parser.parse('2019-01-28 04:13:36.79'),
        initial_btc=0,
        initial_usd=10000,
        num_episodes=1,
        start_dt=parser.parse('2019-01-28 03:13:36.79'),
        time_delta=timedelta(seconds=10)
        )

    test_environment_configs = dict(  #pylint: disable=W0612
        end_dt=parser.parse('2019-01-28 05:13:36.79'),
        initial_btc=0,
        initial_usd=10000,
        num_episodes=1,
        start_dt=parser.parse('2019-01-28 04:13:36.79'),
        time_delta=timedelta(seconds=10)
        )

def evaluate_agent(agent, hyper_params, test_environment_configs):
    """Summary
    
    Args:
        agent (DDPGAgent): Description
        hyper_params (utils.HyperParameters): Description
        test_environment_configs (utils.EnvironmentConfigs): Description
    
    Returns:
        rl.callbacks.Callback: Description
    """
    test_environment = MockEnvironment(
        end_dt=test_environment_configs.end_dt,
        initial_usd=test_environment_configs.initial_usd,
        initial_btc=test_environment_configs.initial_btc, 
        num_workers=c.NUM_DATABASE_WORKERS,
        num_time_steps=hyper_params.num_time_steps,
        start_dt=test_environment_configs.start_dt,
        time_delta=test_environment_configs.time_delta)

    nb_max_episode_steps = utils.calc_nb_max_episode_steps(
        end_dt=test_environment_configs.end_dt,
        start_dt=test_environment_configs.start_dt,
        time_delta=test_environment_configs.time_delta)

    history = agent.test(
        env=test_environment, 
        nb_max_episode_steps=nb_max_episode_steps,
        visualize=False)

    return history

@ex.automain
def main(_run, hyper_params, test_environment_configs, train_environment_configs):
    """Builds a DDPG agent, trains on the train environment, evaluates on the test
    environment, saves model weights as artifacts. Logs artifacts, configuration options,
    and test reward to Sacred.
    
    Args:
        _run (sacred.run.Run): Description
        hyper_params (utils.HyperParameters): Description
        test_environment_configs (utils.EnvironmentConfigs): Description
        train_environment_configs (utils.EnvironmentConfigs): Description
    
    Returns:
        float: Reward from running a single episode on the testing environment
    """
    hyper_params = utils.HyperParameters(**hyper_params)
    test_environment_configs = utils.EnvironmentConfigs(**test_environment_configs)
    train_environment_configs = utils.EnvironmentConfigs(**train_environment_configs)

    tensorboard_dir = utils.make_tensorboard_dir(_run)
    utils.add_tensorboard_dir_to_sacred(ex, tensorboard_dir)
    
    agent, _ = build_and_train(hyper_params, tensorboard_dir, train_environment_configs)

    #Save weights using the agent method. Also save
    #the full actor and critic models in case they're
    #needed later.
    model_dir = utils.make_model_dir(_run)

    actor_save_path = str(model_dir / 'model_actor.hdf5')
    critic_save_path = str(model_dir / 'model_critic.hdf5')
    
    agent.save_weights(filepath=str(model_dir / 'weights.hdf5'))
    agent.actor.save(filepath=actor_save_path)
    agent.critic.save(filepath=critic_save_path)

    _run.add_artifact(str(model_dir / 'weights_actor.hdf5'))
    _run.add_artifact(str(model_dir /'weights_critic.hdf5'))
    _run.add_artifact(str(actor_save_path))
    _run.add_artifact(str(critic_save_path))

    test_history = evaluate_agent(agent, hyper_params, test_environment_configs)

    test_reward = test_history.history['episode_reward'][-1]

    return test_reward
