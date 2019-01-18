"""Summary

Attributes:
    ACCOUNT_PRODUCT (str): Description
    DB_HOST (str): Description
    DB_NAME (str): Description
    ENGINE (sqlalchemy.engine.base.Engine): Description
    POSTGRES_PASSWORD (str): Description
    POSTGRES_USERNAME (str): Description
    PRECISION (Dict[str, int]): Description
    PRODUCT_ID (str): Description
    TAKER_ORDER_FEE_FRACTION (float): Description
"""
import os

from sqlalchemy import create_engine

ACCOUNT_PRODUCT = 'BTC'
# DB_HOST = 'postgres:5432'
DB_HOST = '172.23.0.4:5432'
DB_NAME = 'moonraker'
POSTGRES_USERNAME = os.environ['POSTGRES_USERNAME']
POSTGRES_PASSWORD = os.environ['POSTGRES_PASSWORD']
PRECISION = dict(BTC=5, USD=2)
PRODUCT_ID = 'BTC-USD'
TAKER_ORDER_FEE_FRACTION = 0.003

ENGINE = create_engine(
    f'postgresql://{POSTGRES_USERNAME}:{POSTGRES_PASSWORD}@'
    f'{DB_HOST}/{DB_NAME}')
