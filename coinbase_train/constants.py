"""Summary

Attributes:
    ILLEGAL_TRANSACTION_PENALTY (float): Description
    MAX_PERCENT_OF_FUNDS_TRANSACTED_PER_STEP (float): Description
    MAX_PRICE (float): Description
    MONGO_DB_HOST (str): Description
    MONGO_DB_PASSWORD (str): Description
    MONGO_DB_PORT (int): Description
    MONGO_DB_URL (str): Description
    MONGO_DB_USERNAME (str): Description
    ACTOR_OUTPUT_DIMENSION (int): Description
    NUM_CHANNELS_IN_TIME_SERIES (int): Description
    NUM_DATABASE_WORKERS (int): Description
    ORDER_BOOK_BIN_SIZE (Decimal): Description
    PRODUCT_ID (str): Description
    SAVED_MODELS_ROOT_DIR (str): Description
    TENSORBOARD_ROOT_DIR (str): Description
    VERBOSE (bool): Description
"""
from decimal import Decimal
import os

MAX_PERCENT_OF_FUNDS_TRANSACTED_PER_STEP = 0.1
NUM_CHANNELS_IN_TIME_SERIES = 26
ORDER_BOOK_BIN_SIZE = Decimal('1.00')
ILLEGAL_TRANSACTION_PENALTY = 1e3 #should be positive
PRODUCT_ID = 'BTC-USD'
PRODUCT_CURRENCY, QUOTE_CURRENCY = PRODUCT_ID.split('-')
MAX_TRANSACTIONS = 9
NORMALIZERS = dict(BTC_FUNDS=1e1, PRICE=10e3, SIZE=1e2, USD_FUNDS=100e3)
NUM_ACTIONS = 8 
ACTOR_OUTPUT_DIMENSION = NUM_ACTIONS + MAX_TRANSACTIONS + 1
NUM_DATABASE_WORKERS = 3
VERBOSE = True

#logging configs
SAVED_MODELS_ROOT_DIR = '/var/moonraker_models'
TENSORBOARD_ROOT_DIR = '/var/log/sacred_tensorboard'

#mongodb configs
MONGO_DB_HOST = os.environ.get('MONGO_DB_HOST', 'sacred-mongodb')
MONGO_DB_PASSWORD = os.environ.get('MONGO_INITDB_ROOT_PASSWORD', 'password')
MONGO_DB_PORT = os.environ.get('MONGO_DB_PORT', 27017)
MONGO_DB_USERNAME = os.environ.get('MONGO_DB_USERNAME', 'root')
MONGO_DB_URL = f'mongodb://{MONGO_DB_USERNAME}:{MONGO_DB_PASSWORD}@{MONGO_DB_HOST}:{MONGO_DB_PORT}' #pylint: disable=C0301
