"""Summary
"""
import os

from coinbase_ml.common import constants as cc

NUM_DATABASE_WORKERS = 3
VERBOSE = False
EXPERIMENT_NAME = f"{str(cc.PRODUCT_ID).lower()}-train"

# ray configs
LOCAL_MODE = os.environ.get("LOCAL_MODE") in ["True", "true", "TRUE"]
NUM_GPUS = int(os.environ.get("NUM_GPUS", 0))
RAY_OBJECT_STORE_MEMORY = os.environ.get("RAY_OBJECT_STORE_MEMORY")
RAY_REDIS_ADDRESS = os.environ.get("RAY_REDIS_ADDRESS")
