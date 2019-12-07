"""
types.py
"""
from typing import TypeVar

from fakebase.base_classes import AccountBase, ExchangeBase

Account = TypeVar("Account", bound=AccountBase)
Exchange = TypeVar("Exchange", bound=ExchangeBase)
