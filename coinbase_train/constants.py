"""Summary

Attributes:
    ACCOUNT_PRODUCT (str): Description
    END_DT (TYPE): Description
    NUM_DATABASE_WORKERS (int): Description
    NUM_TIME_STEPS (int): Description
    PAD_ORDER_BOOK_TO_LENGTH (int): Description
    PRECISION (TYPE): Description
    PRODUCT_ID (str): Description
    START_DT (TYPE): Description
    TIME_DELTA (TYPE): Description
"""
import os


ACCOUNT_PRODUCT = 'BTC'
PRODUCT_ID = 'BTC-USD'
PAD_ORDER_BOOK_TO_LENGTH = 1000
PRECISION = dict(BTC=5, USD=2)
NUM_ACTIONS = 5
NUM_DATABASE_WORKERS = 4
NUM_TIME_STEPS = 3

#logging configs
SAVED_MODELS_ROOT_DIR = '/var/moonraker_models'
TENSORBOARD_ROOT_DIR = '/var/log/sacred_tensorboard'

#mongodb configs
MONGO_INITDB_ROOT_PASSWORD = os.environ['MONGO_INITDB_ROOT_PASSWORD']
MONGO_INITDB_ROOT_USERNAME = os.environ['MONGO_INITDB_ROOT_USERNAME']
#MONGO_DB_URL = f'mongodb://{MONGO_INITDB_ROOT_USERNAME}:{MONGO_INITDB_ROOT_PASSWORD}@mongo:27017'
MONGO_DB_URL = f'mongodb://{MONGO_INITDB_ROOT_USERNAME}:{MONGO_INITDB_ROOT_PASSWORD}@172.23.0.2:27017'