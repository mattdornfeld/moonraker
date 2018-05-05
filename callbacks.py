from collections import defaultdict
import numpy as np

from gdax_train.lib.rl.callbacks import Callback

class History(Callback):
    def on_train_begin(self, logs={}):
        self.actions = defaultdict(list)
        self.metrics = defaultdict(list)
        self.metrics_names = self.model.metrics_names
        self.exchange_observations = defaultdict(list)
        self.wallet_observations = defaultdict(list)
        self.rewards = defaultdict(list)
        self.total_reward = dict()

    def on_step_end(self, step, logs={}):
        episode = logs.get('episode')
        self.actions[episode].append(logs.get('action'))
        self.metrics[episode].append(logs.get('metrics'))
        self.exchange_observations[episode].append(logs.get('observation')[0])
        self.wallet_observations[episode].append(logs.get('observation')[1])
        self.rewards[episode].append(logs.get('reward'))

    def on_episode_end(self, episode, logs={}):
        self.actions[episode] = np.array(self.actions[episode])
        self.metrics[episode] = np.array(self.metrics[episode])
        self.rewards[episode] = np.array(self.rewards[episode])
        self.total_reward[episode] = sum(self.rewards[episode])

