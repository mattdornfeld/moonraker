"""
Module that contains fakebase orms
"""
from sqlalchemy_utils import database_exists, create_database

from .. import constants as c
from ..orm.cancellation import CoinbaseCancellation
from ..orm.match import CoinbaseMatch
from ..orm.mixins import Base, CoinbaseEvent
from ..types import ProductPrice, ProductVolume, QuoteVolume
from ..orm.order import CoinbaseOrder


def create_db_and_tables() -> None:
    """Summary
    """
    if not database_exists(c.ENGINE.url):
        create_database(c.ENGINE.url)

    Base.metadata.create_all(c.ENGINE)
