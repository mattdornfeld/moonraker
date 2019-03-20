"""Summary

Attributes:
    ACCOUNT_PRODUCT (str): Description
    MONGO_DB_HOST (TYPE): Description
    MONGO_DB_PASSWORD (TYPE): Description
    MONGO_DB_PORT (TYPE): Description
    MONGO_DB_URL (TYPE): Description
    MONGO_DB_USERNAME (TYPE): Description
    NUM_ACTIONS (int): Description
    NUM_DATABASE_WORKERS (int): Description
    NUM_TIME_STEPS (int): Description
    PAD_ORDER_BOOK_TO_LENGTH (int): Description
    PRODUCT_ID (str): Description
    SAVED_MODELS_ROOT_DIR (str): Description
    TENSORBOARD_ROOT_DIR (str): Description

Deleted Attributes:
    END_DT (TYPE): Description
    PRECISION (TYPE): Description
    START_DT (TYPE): Description
    TIME_DELTA (TYPE): Description
"""
import os

ACCOUNT_PRODUCT = 'BTC'
BATCH_SIZE = 1
FIAT_CURRENCY = 'USD'
PRODUCT_ID = 'BTC-USD'
NUM_ACTIONS = 5
NUM_DATABASE_WORKERS = 3
NUM_TIME_STEPS = 3

#logging configs
SAVED_MODELS_ROOT_DIR = '/var/moonraker_models'
TENSORBOARD_ROOT_DIR = '/var/log/sacred_tensorboard'

#mongodb configs
MONGO_DB_HOST = os.environ.get('MONGO_DB_HOST', 'mongo')
MONGO_DB_PASSWORD = os.environ.get('MONGO_DB_PASSWORD', 'password')
MONGO_DB_PORT = os.environ.get('MONGO_DB_PORT', 27017)
MONGO_DB_USERNAME = os.environ.get('MONGO_DB_USERNAME', 'mongo')
MONGO_DB_URL = f'mongodb://{MONGO_DB_USERNAME}:{MONGO_DB_PASSWORD}@{MONGO_DB_HOST}:{MONGO_DB_PORT}' #pylint: disable=C0301
