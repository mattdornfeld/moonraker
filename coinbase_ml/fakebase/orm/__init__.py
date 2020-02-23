"""
Module that contains fakebase orms
"""
from sqlalchemy_utils import database_exists, create_database

from fakebase import constants as c
from fakebase.orm.cancellation import CoinbaseCancellation
from fakebase.orm.match import CoinbaseMatch
from fakebase.orm.mixins import Base, CoinbaseEvent
from fakebase.types import ProductPrice, ProductVolume, QuoteVolume
from fakebase.orm.order import CoinbaseOrder


def create_db_and_tables() -> None:
    """Summary
    """
    if not database_exists(c.ENGINE.url):
        create_database(c.ENGINE.url)

    Base.metadata.create_all(c.ENGINE)
