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
from datetime import timedelta
from decimal import Decimal
import os
from pathlib import Path

ACTOR_OUTPUT_DIMENSION = 4
BUY_RESERVE_FRACTION = Decimal('0.005')
ILLEGAL_TRANSACTION_PENALTY = 1e3 #should be positive
MAX_PRICE = 13000.00
NORMALIZERS = dict(BTC_FUNDS=1e1, PRICE=10e3, SIZE=1e2, USD_FUNDS=100e3)
NUM_DATABASE_WORKERS = 3
NUM_CHANNELS_IN_TIME_SERIES = 26
ORDER_BOOK_BIN_SIZE = Decimal('0.01')
ORDER_BOOK_DEPTH = 10
ORDER_TIME_TO_LIVE = timedelta(minutes=0.5)
PRODUCT_ID = 'BTC-USD'
VERBOSE = False
EXPERIMENT_NAME = PRODUCT_ID.lower() + '-train'
PRODUCT_CURRENCY, QUOTE_CURRENCY = PRODUCT_ID.split('-')

#gcp configs
GCP_PROJECT_NAME = 'moonraker'
MODEL_BUCKET_NAME = os.environ.get('MODEL_BUCKET_NAME', 'moonraker-trained-models')
SERVICE_ACCOUNT_JSON = Path(os.environ.get('SERVICE_ACCOUNT_JSON', '/secrets/service-account.json'))

#logging configs
SAVED_MODELS_ROOT_DIR = '/var/moonraker_models'
TENSORBOARD_ROOT_DIR = '/var/log/sacred_tensorboard'

#mongodb configs
MONGO_DB_URL = os.environ.get("MONGO_DB_URL", "mongodb://root:password@mongo:27017")
