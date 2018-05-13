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

    callbacks = [History()]

    nb_max_episode_steps = int((end_dt - start_dt) / time_delta) - 1

    agent.test(
        callbacks=callbacks, 
        env=env, 
        nb_max_episode_steps=nb_max_episode_steps,
        visualize=False)

    return callbacks

class History(Callback):
    def on_train_begin(self, logs={}):
        self.metrics = []
        self.metrics_names = self.model.metrics_names
        self.rewards = []

    def on_step_end(self, step, logs={}):
        self.metrics.append(logs.get('metrics'))
        self.rewards.append(logs.get('reward'))

    def on_episode_end(self, episode, logs={}):
        self.episode_reward = sum(self.rewards)   

class TestLogger(Callback):
    def __init__(self, sacred_experiment, test_start_dt, test_end_dt, time_delta):
        self.sacred_experiment = sacred_experiment
        self.test_start_dt = test_start_dt
        self.test_end_dt = test_end_dt
        self.time_delta = time_delta

        super().__init__()

    def on_train_begin(self, logs={}):
        self.metrics = []
        self.metrics_names = self.model.metrics_names
        self.rewards = []

    def on_episode_end(self, episode, logs={}):
        #At the end of each episode evaluate the performance of the agent
        test_history = evaluate_agent(
            agent=self.model,
            start_dt=self.test_start_dt,
            end_dt=self.test_end_dt,
            time_delta=self.time_delta)[0]

        self.metrics.append(test_history.metrics[-1])
        self.rewards.append(test_history.episode_reward)

        from IPython import embed; embed()
        self.sacred_experiment.log_scalar('test_reward', self.rewards[-1])
        for i, metric_name in enumerate(self.metrics_names):
            _metric_name = 'train_' + metric_name
            metric = self.metrics[-1][i]
            self.sacred_experiment.log_scalar(_metric_name, metric)

class TrainLogger(Callback):
    def __init__(self, sacred_experiment):
        self.sacred_experiment = sacred_experiment
        super().__init__()

    def on_train_begin(self, logs={}):
        self.metrics = []
        self.metrics_names = self.model.metrics_names
        self.rewards = []

    def on_step_end(self, step, logs={}):
        self.metrics.append(logs.get('metrics'))
        self.rewards.append(logs.get('reward'))

    def on_episode_end(self, episode, logs={}):
        self.sacred_experiment.log_scalar('train_reward', sum(self.rewards))
        for i, metric_name in enumerate(test_history.metrics_names):
            _metric_name = 'test_' + metric_name
            metric = test_history.metrics[-1][i]
            self.sacred_experiment.log_scalar(_metric_name, metric)