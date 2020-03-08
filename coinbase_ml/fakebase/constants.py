"""Summary
"""
import os
from decimal import Decimal

from sqlalchemy import create_engine

# Coinbase configs
COINBASE_WEBSOCKET_API_URL = "wss://ws-feed.pro.coinbase.com"
COINBASE_WEBSOCKET_CHANNELS = ["full", "level2"]

# Postgres configs
DB_HOST = os.environ.get("DB_HOST", "postgres")
DB_NAME = os.environ.get("DB_NAME", "moonraker")
DB_PORT = os.environ.get("DB_PORT", "5432")
NUM_DATABASE_WORKERS = 3
DATABASE_RESULTS_QUEUE_SIZE = 50
POSTGRES_USERNAME = os.environ.get("POSTGRES_USERNAME", "postgres")
POSTGRES_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "password")
SQLALCHEMY_POOL_SIZE = int(os.environ.get("SQLALCHEMY_POOL_SIZE", "100"))
ENGINE = create_engine(
    f"postgresql://{POSTGRES_USERNAME}:{POSTGRES_PASSWORD}@"
    f"{DB_HOST}:{DB_PORT}/{DB_NAME}",
    pool_size=SQLALCHEMY_POOL_SIZE,
)

ZERO_DECIMAL = Decimal("0.0")