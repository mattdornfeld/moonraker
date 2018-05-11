from collections import defaultdict
import numpy as np

from gdax_train.lib.rl.callbacks import Callback
from gdax_train.train import evaluate_agent

# class History(Callback):
#     def on_train_begin(self, logs={}):
#         self.actions = defaultdict(list)
#         self.metrics = defaultdict(list)
#         self.metrics_names = self.model.metrics_names
#         self.exchange_observations = defaultdict(list)
#         self.wallet_observations = defaultdict(list)
#         self.rewards = defaultdict(list)
#         self.total_reward = dict()

#     def on_step_end(self, step, logs={}):
#         episode = logs.get('episode')
#         self.actions[episode].append(logs.get('action'))
#         self.metrics[episode].append(logs.get('metrics'))
#         self.exchange_observations[episode].append(logs.get('observation')[0])
#         self.wallet_observations[episode].append(logs.get('observation')[1])
#         self.rewards[episode].append(logs.get('reward'))

#     def on_episode_end(self, episode, logs={}):
#         from IPython import embed; embed()
#         self.actions[episode] = np.array(self.actions[episode])
#         self.metrics[episode] = np.array(self.metrics[episode])
#         self.rewards[episode] = np.array(self.rewards[episode])
#         self.total_reward[episode] = sum(self.rewards[episode])

class History(Callback):
    def on_train_begin(self, logs={}):
        self.rewards = []
        self.metrics = []

    def on_step_end(self, step, logs={}):
        self.metrics.append(logs.get('metrics'))
        self.rewards[episode].append(logs.get('reward'))

    def on_episode_end(self, episode, logs={}):
        self.episode_reward = sum(self.rewards)
        self.episode_loss = self.metrics[-1][0]
        self.episode_mean_q = self.metrics[-1][1]


class SacredLogger(History):
    def __init__(self, sacred_experiment, test_start_dt, test_end_dt, time_delta):
        self.sacred_experiment = sacred_experiment
        self.test_start_dt = test_start_dt
        self.test_end_dt = test_end_dt
        self.time_delta = time_delta

        super().__init__()

    def on_episode_end(self, episode, logs={}):
        super()

        #At the end of each episode evaluate the performance of the agent
        test_history = evaluate_agent(
            agent=self.model,
            start_dt=self.test_start_dt,
            end_dt=self.test_end_dt,
            time_delta=self.time_delta)[0]

        self.sacred_experiment.log_scalar('test_loss', test_callbacks.loss)
        self.sacred_experiment.log_scalar('test_mean_q', test_callbacks.mean_q)
        self.sacred_experiment.log_scalar('test_reward', test_callbacks.reward)
        self.sacred_experiment.log_scalar('train_loss', test_callbacks.loss)
        self.sacred_experiment.log_scalar('train_mean_q', test_callbacks.mean_q)
        self.sacred_experiment.log_scalar('train_reward', test_callbacks.reward)



        from IPython import embed; embed()
        self.actions[episode] = np.array(self.actions[episode])
        self.metrics[episode] = np.array(self.metrics[episode])
        self.rewards[episode] = np.array(self.rewards[episode])
        self.total_reward[episode] = sum(self.rewards[episode])