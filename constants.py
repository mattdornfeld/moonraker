from datetime import datetime
import numpy as np
import os

def generate_one_hot_dict(categories):

	vectors = np.eye(len(categories))
	one_hot_dict = {}

	for i, category in enumerate(categories):

		one_hot_dict[category] = vectors[i]

	return one_hot_dict

#postgres configs
DB_NAME = 'moonraker'
DB_HOST = 'postgres:5432'
# DB_HOST = '172.23.0.3:5432'
POSTGRES_USERNAME = os.environ['POSTGRES_USERNAME']
POSTGRES_PASSWORD = os.environ['POSTGRES_PASSWORD']

#mongodb configs
MONGO_INITDB_ROOT_PASSWORD = os.environ['MONGO_INITDB_ROOT_PASSWORD']
MONGO_INITDB_ROOT_USERNAME = os.environ['MONGO_INITDB_ROOT_USERNAME']
MONGO_DB_URL = 'mongodb://{}:{}@mongo:27017'.format(MONGO_INITDB_ROOT_USERNAME, MONGO_INITDB_ROOT_PASSWORD)
# MONGO_DB_URL = 'mongodb://{}:{}@172.23.0.2:27017'.format(MONGO_INITDB_ROOT_USERNAME, MONGO_INITDB_ROOT_PASSWORD)

#logging configs
TENSORBOARD_ROOT_DIR = '/var/log/sacred_tensorboard'
SAVED_MODELS_ROOT_DIR = '/var/moonraker_models'

#agent configs
DDPG_BUFFER_SIZE = 100000
DDPG_WINDOW_LENGTH = 1
NUM_STEPS_WARMUP_ACTOR = 1
NUM_STEPS_WARMUP_CRITIC = 1

#environment configs
ENV_BUFFER_SIZE = 100
EVENT_TYPES = ['received', 'open', 'match']
EVENT_TYPE_VECTORS = generate_one_hot_dict(EVENT_TYPES)
INITIAL_PRODUCT_AMOUNT = 0
INITIAL_USD = 100
NULL_DATETIME = datetime(year=1970, month=1, day=1)
NULL_ID = None
NUM_EVENTS_PER_TIME_STEP = None
NUM_EXCHANGE_FEATURES = 7
NUM_METADATA_FEATURES = 2 # (event_datetime, order_id)
NUM_TIME_STEPS = 3
NUM_WALLET_FEATURES = 7
NUM_WORKERS = 3
ORDER_SIDES = ['buy', 'sell']
ORDER_SIDE_VECTORS = generate_one_hot_dict(ORDER_SIDES)

#model configs
GDAX_BATCH_SHAPE = (None, NUM_TIME_STEPS, NUM_EVENTS_PER_TIME_STEP, NUM_EXCHANGE_FEATURES)
NUM_ACTIONS = 2
WALLET_BATCH_SHAPE = (None, NUM_TIME_STEPS, NUM_EVENTS_PER_TIME_STEP, NUM_WALLET_FEATURES)