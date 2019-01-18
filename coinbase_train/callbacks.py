"""Summary
"""
from keras import backend as K
import tensorflow as tf

from lib.rl.callbacks import Callback

class TrainLogger(Callback):

    """Summary
    
    Attributes:
        episode_metrics (List[Dict[str, float]]): Description
        episode_rewards (List[float]): Description
        file_writer (tf.summary.FileWriter): Description
        metrics (List[Dict[str, float]]): Description
        metrics_names (List[str]): Description
        sacred_experiment (sacred.Experiment): Description
        sess (tensorflow.python.client.session.Session): Description
        tensorboard_dir (pathlib.Path): Description
    """
    
    def __init__(self, sacred_experiment, tensorboard_dir):
        """Summary
        
        Args:
            sacred_experiment (sacred.Experiment): Description
            tensorboard_dir (pathlib.Path): Description
        """
        self.sacred_experiment = sacred_experiment
        self.tensorboard_dir = str(tensorboard_dir)
        super().__init__()

    @staticmethod
    def _create_tensorboard_summary(name, value):
        """Summary
        
        Args:
            name (str): Description
            value (float): Description
        
        Returns:
            tf.Summary: Description
        """
        summary = tf.Summary()
        summary_value = summary.value.add() #pylint: disable=E1101
        summary_value.simple_value = value
        summary_value.tag = name

        return summary

    def on_train_begin(self, logs={}): #pylint: disable=W0102
        """Summary
        
        Args:
            logs (dict, optional): Description
        """
        self.episode_metrics = [] #pylint: disable=W0201
        self.episode_rewards = [] #pylint: disable=W0201
        self.metrics = [] #pylint: disable=W0201
        self.metrics_names = self.model.metrics_names #pylint: disable=W0201
        self.sess = K.get_session() #pylint: disable=W0201
        self.file_writer = tf.summary.FileWriter(logdir=self.tensorboard_dir, graph=self.sess.graph) #pylint: disable=W0201

    def on_step_end(self, step, logs={}): #pylint: disable=W0102
        """Summary
        
        Args:
            step (int): Description
            logs (dict, optional): Description
        """
        self.metrics.append(logs.get('metrics'))

    def on_episode_end(self, episode, logs={}): #pylint: disable=W0102
        """Summary
        
        Args:
            episode (int): Description
            logs (dict, optional): Description
        """
        self.episode_metrics.append(self.metrics[-1])
        self.episode_rewards.append(logs.get('episode_reward'))

        for i, metric_name in enumerate(self.metrics_names):
            _metric_name = f'train_{metric_name}'

            metric = self.episode_metrics[-1][i]
            
            self.sacred_experiment.log_scalar(_metric_name, float(metric))
            
            summary = self._create_tensorboard_summary(name=metric_name, value=metric)
            
            self.file_writer.add_summary(summary, episode)

        self.sacred_experiment.log_scalar('train_reward', self.episode_rewards[-1])
        
        summary = self._create_tensorboard_summary(
            name='train_reward', value=self.episode_rewards[-1])
        
        self.file_writer.add_summary(summary, episode)
        
        self.file_writer.flush()

    def on_train_end(self, logs={}): #pylint: disable=W0102
        """Summary
        
        Args:
            logs (dict, optional): Description
        """
        self.file_writer.close()
