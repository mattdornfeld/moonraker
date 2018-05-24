from keras import backend as K
from sacred.stflow import LogFileWriter
import tensorflow as tf

from gdax_train.constants import *
from gdax_train.lib.rl.callbacks import Callback
from gdax_train.environment import Wallet, MockExchange

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

    nb_max_episode_steps = int((end_dt - start_dt) / time_delta) - 1

    history = agent.test(
        env=env, 
        nb_max_episode_steps=nb_max_episode_steps,
        visualize=False)

    return history

class TestLogger(Callback):
    def __init__(self, sacred_experiment, tensorboard_dir, test_start_dt, test_end_dt, time_delta):
        self.sacred_experiment = sacred_experiment
        self.tensorboard_dir = tensorboard_dir
        self.test_start_dt = test_start_dt
        self.test_end_dt = test_end_dt
        self.time_delta = time_delta

        super().__init__()

    def on_train_begin(self, logs={}):
        self.episode_rewards = []
        self.sess = K.get_session()
        with LogFileWriter(self.sacred_experiment):
            self.file_writer = tf.summary.FileWriter(logdir=self.tensorboard_dir)

    def on_episode_end(self, episode, logs={}):
        #At the end of each episode evaluate the performance of the agent
        test_history = evaluate_agent(
            agent=self.model,
            start_dt=self.test_start_dt,
            end_dt=self.test_end_dt,
            time_delta=self.time_delta)

        self.episode_rewards.append(test_history.history['episode_reward'][-1])

        self.sacred_experiment.log_scalar('test_reward', self.episode_rewards[-1])

        test_reward = tf.summary.scalar('test_reward', self.episode_rewards[-1])
        summary_op = tf.summary.merge(inputs=[test_reward])
        summary = self.sess.run([summary_op])
        self.file_writer.add_summary(summary=summary[0], global_step=episode)


class TrainLogger(Callback):
    def __init__(self, sacred_experiment, tensorboard_dir):
        self.sacred_experiment = sacred_experiment
        self.tensorboard_dir = tensorboard_dir
        super().__init__()

    def on_train_begin(self, logs={}):
        self.episode_metrics = []
        self.episode_rewards = []
        self.metrics = []
        self.metrics_names = self.model.metrics_names
        self.sess = K.get_session()
        self.file_writer = tf.summary.FileWriter(logdir=self.tensorboard_dir, graph=self.sess.graph)

    def on_step_end(self, step, logs={}):
        self.metrics.append(logs.get('metrics'))

    def on_episode_end(self, episode, logs={}):
        self.episode_metrics.append(self.metrics[-1])
        self.episode_rewards.append(logs.get('episode_reward'))
        
        _summaries = []
        for i, metric_name in enumerate(self.metrics_names):
            _metric_name = 'train_' + metric_name
            metric = self.episode_metrics[-1][i]
            self.sacred_experiment.log_scalar(_metric_name, float(metric))
            metric_summary = tf.summary.scalar(metric_name, metric)
            _summaries.append(metric_summary)

        self.sacred_experiment.log_scalar('train_reward', self.episode_rewards[-1])
        train_reward_summary = tf.summary.scalar('train_reward', self.episode_rewards[-1])
        _summaries.append(train_reward_summary)

        summary_op = tf.summary.merge(_summaries)
        summary = self.sess.run([summary_op])
        self.file_writer.add_summary(summary=summary[0], global_step=episode)