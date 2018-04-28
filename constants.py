import numpy as np
from datetime import datetime

def generate_one_hot_dict(categories):

	vectors = np.eye(len(categories))
	one_hot_dict = {}

	for i, category in enumerate(categories):

		one_hot_dict[category] = vectors[i]

	return one_hot_dict

#postgres configs
DB_HOST = 'postgres:5432'
DB_NAME = 'bitcoin'

#model train configs
EVENT_TYPES = ['received', 'open', 'match']
ORDER_SIDES = ['buy', 'sell']
EVENT_TYPE_VECTORS = generate_one_hot_dict(EVENT_TYPES)
ORDER_SIDE_VECTORS = generate_one_hot_dict(ORDER_SIDES)
NUM_EXCHANGE_FEATURES = 7
NUM_METADATA_FEATURES = 2 # (event_datetime, order_id)
NUM_WALLET_FEATURES = 7
NULL_DATETIME = datetime(year=1970, month=1, day=1)
NULL_ID = None
BATCH_SIZE = 5
NUM_TIME_STEPS = 3
NUM_EVENTS_PER_TIME_STEP = None
NUM_ACTIONS = 2
GDAX_BATCH_SHAPE = (None, NUM_TIME_STEPS, NUM_EVENTS_PER_TIME_STEP, NUM_EXCHANGE_FEATURES)
WALLET_BATCH_SHAPE = (None, NUM_TIME_STEPS, NUM_EVENTS_PER_TIME_STEP, NUM_WALLET_FEATURES)
DDPG_BUFFER_SIZE = 100000
DDPG_WINDOW_LENGTH = 1