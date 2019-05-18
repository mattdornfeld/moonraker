"""Summary

Attributes:
    PRODUCT_CURRENCY (str): Description
    BATCH_SIZE (int): Description
    FIAT_CURRENCY (str): Description
    ILLEGAL_TRANSACTION_PENALTY (float): Description
    MAX_PERCENT_OF_FUNDS_TRANSACTED_PER_STEP (float): Description
    MAX_PRICE (float): Description
    MONGO_DB_HOST (TYPE): Description
    MONGO_DB_PASSWORD (TYPE): Description
    MONGO_DB_PORT (TYPE): Description
    MONGO_DB_URL (TYPE): Description
    MONGO_DB_USERNAME (TYPE): Description
    NUM_ACTIONS (int): Description
    NUM_DATABASE_WORKERS (int): Description
    NUM_TIME_STEPS (int): Description
    PRODUCT_ID (str): Description
    SAVED_MODELS_ROOT_DIR (str): Description
    TENSORBOARD_ROOT_DIR (str): Description
"""
import os

PRODUCT_CURRENCY = 'BTC'
MAX_PERCENT_OF_FUNDS_TRANSACTED_PER_STEP = 0.1
MAX_PRICE = 10e3
FIAT_CURRENCY = 'USD'
ILLEGAL_TRANSACTION_PENALTY = 1e3 #should be positive
PRODUCT_ID = 'BTC-USD'
NUM_ACTIONS = 14
NUM_DATABASE_WORKERS = 3
NUM_TIME_STEPS = 3

#logging configs
SAVED_MODELS_ROOT_DIR = '/var/moonraker_models'
TENSORBOARD_ROOT_DIR = '/var/log/sacred_tensorboard'

#mongodb configs
MONGO_DB_HOST = os.environ.get('MONGO_DB_HOST', 'sacred-mongodb')
MONGO_DB_PASSWORD = os.environ.get('MONGO_INITDB_ROOT_PASSWORD', 'password')
MONGO_DB_PORT = os.environ.get('MONGO_DB_PORT', 27017)
MONGO_DB_USERNAME = os.environ.get('MONGO_DB_USERNAME', 'root')
MONGO_DB_URL = f'mongodb://{MONGO_DB_USERNAME}:{MONGO_DB_PASSWORD}@{MONGO_DB_HOST}:{MONGO_DB_PORT}' #pylint: disable=C0301
