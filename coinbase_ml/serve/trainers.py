"""
trainers.py
"""
from typing import Dict, Type

from ray.rllib.agents import Trainer
from ray.rllib.agents.ppo import PPOTrainer

TRAINERS: Dict[str, Type[Trainer]] = dict(ppo=PPOTrainer)
