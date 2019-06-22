"""Summary
"""
from pathlib import Path
from typing import List, Tuple

from keras import Model
from keras.optimizers import SGD
from sacred.run import Run

from lib.rl.agents import DDPGAgent
from lib.rl.callbacks import Callback
from lib.rl.memory import SequentialMemory
from lib.rl.random import OrnsteinUhlenbeckProcess

from coinbase_train import constants as c
from coinbase_train import utils
from coinbase_train.callbacks import TrainLogger
from coinbase_train.environment import MockEnvironment
from coinbase_train.experiment import config, ex
from coinbase_train.layers import Attention
from coinbase_train.model import build_actor, build_critic
from coinbase_train.processor import CoibaseEnvironmentProcessor

NUM_TIME_STEPS = config()['hyper_params']['num_time_steps']

def create_agent(actor: Model, 
                 critic: Model, 
                 hyper_params: utils.HyperParameters) -> DDPGAgent:
    """Summary
    
    Args:
        actor (Model): Description
        critic (Model): Description
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

    agent.compile(SGD(lr=hyper_params.learning_rate, clipnorm=0.1))

    return agent

def build_and_train(
        hyper_params: utils.HyperParameters, 
        tensorboard_dir: Path, 
        train_environment_configs: utils.EnvironmentConfigs) -> Tuple[DDPGAgent, List[Callback]]:
    """Summary
    
    Args:
        hyper_params (utils.HyperParameters): Description
        tensorboard_dir (Path): Description
        train_environment_configs (utils.EnvironmentConfigs): Description
    
    Returns:
        Tuple[DDPGAgent, List[Callback]]: Description
    """

    actor = build_actor(
        attention_dim=hyper_params.attention_dim,
        depth=hyper_params.depth,
        num_filters=hyper_params.num_filters,
        num_stacks=hyper_params.num_stacks)

    critic = build_critic(
        attention_dim=hyper_params.attention_dim,
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
        verbose=c.VERBOSE)

    agent = create_agent(
        actor=actor,
        critic=critic,
        hyper_params=hyper_params)

    nb_max_episode_steps = utils.calc_nb_max_episode_steps(
        end_dt=train_environment_configs.end_dt,
        num_time_steps=NUM_TIME_STEPS,
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

def evaluate_agent(
        agent: DDPGAgent, 
        hyper_params: utils.HyperParameters, 
        test_environment_configs: utils.EnvironmentConfigs) -> Callback:
    """Summary
    
    Args:
        agent (DDPGAgent): Description
        hyper_params (utils.HyperParameters): Description
        test_environment_configs (utils.EnvironmentConfigs): Description
    
    Returns:
        Callback: Description
    """
    test_environment = MockEnvironment(
        end_dt=test_environment_configs.end_dt,
        initial_btc=test_environment_configs.initial_btc, 
        initial_usd=test_environment_configs.initial_usd,
        num_time_steps=hyper_params.num_time_steps,
        num_workers=c.NUM_DATABASE_WORKERS,
        start_dt=test_environment_configs.start_dt,
        time_delta=test_environment_configs.time_delta,
        verbose=c.VERBOSE)

    nb_max_episode_steps = utils.calc_nb_max_episode_steps(
        end_dt=test_environment_configs.end_dt,
        num_time_steps=NUM_TIME_STEPS,
        start_dt=test_environment_configs.start_dt,
        time_delta=test_environment_configs.time_delta)

    history = agent.test(
        env=test_environment, 
        nb_max_episode_steps=nb_max_episode_steps,
        visualize=False)

    return history

@ex.automain
def main(_run: Run, 
         hyper_params: dict, 
         test_environment_configs: dict, 
         train_environment_configs: dict) -> float:
    """Builds a DDPG agent, trains on the train environment, evaluates on the test
    environment, saves model weights as artifacts. Logs artifacts, configuration options,
    and test reward to Sacred.
    
    Args:
        _run (Run): Description
        hyper_params (dict): Description
        test_environment_configs (dict): Description
        train_environment_configs (dict): Description
    
    Returns:
        float: Reward from running a single episode on the testing environment
    """
    _hyper_params = utils.HyperParameters(**hyper_params)
    _test_environment_configs = utils.EnvironmentConfigs(**test_environment_configs)
    _train_environment_configs = utils.EnvironmentConfigs(**train_environment_configs)

    tensorboard_dir = utils.make_tensorboard_dir(_run)
    utils.add_tensorboard_dir_to_sacred(ex, tensorboard_dir)
    
    agent, _ = build_and_train(_hyper_params, tensorboard_dir, _train_environment_configs)

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

    test_history = evaluate_agent(agent, _hyper_params, _test_environment_configs)

    test_reward = test_history.history['episode_reward'][-1]

    return test_reward
