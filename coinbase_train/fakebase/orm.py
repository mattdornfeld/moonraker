"""Summary

Attributes:
    Base (sqlalchemy.ext.declarative.api.Base): Description
"""
from datetime import datetime

import numpy as np
from sqlalchemy import Column, BigInteger, String, DateTime, Float
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import reconstructor
from sqlalchemy_utils import database_exists, create_database

from coinbase_train.fakebase import constants as c
from coinbase_train.fakebase import utils

Base = declarative_base()

class CoinbaseMatch(Base): #pylint: disable=R0903

    """Model for storing Coinbase Matches
    
    
    Attributes:
        liquidity (str): 'M' if your order was the maker order. 'T' if it was the taker order.
        maker_order_id (Column[String]): Description
        price (Column[Float]): Description
        product_id (Column[String]): Description
        row_id (Column[BigInteger]): Description
        sequence (Column[String]): Description
        side (Column[String]): Side of the maker order
        size (Column[Float]): Description
        taker_order_id (Column[String]): Description
        time (Column[Datetime]): Description
        trade_id (Column[BigInteger]): Description
    """
    
    __tablename__ = 'coinbase_matches'

    maker_order_id = Column(String)
    price = Column(Float)
    product_id = Column(String)
    row_id = Column(BigInteger, primary_key=True, autoincrement=True)
    sequence = Column(String)
    side = Column(String)
    size = Column(Float)
    taker_order_id = Column(String)
    time = Column(DateTime)
    trade_id = Column(BigInteger)

    def __init__(self, liquidity=None, **kwargs):
        """Summary
        
        Args:
            liquidity (None, optional): Description
            **kwargs: Description
        """
        self.liquidity = liquidity

        super().__init__(**kwargs)

    @staticmethod
    def _get_array_represenation(price, side, size, time):
        """Summary
        
        Args:
            price (float): Description
            side (str): Description
            size (float): Description
            time (datetime): Description
        
        Returns:
            np.ndarray: Description
        """
        _array = (
            [price, size] + 
            utils.one_hot_encode_order_side(side) +  
            utils.preprocess_time(time)
            )

        return np.array(_array)

    @property
    def account_order_side(self):
        """'buy' if you placed a buy order. 'sell' if you placed a sell order.side
        Different from self.side, which indicates the side of the maker order.
        
        Returns:
            str: Description
        """
        return (self.side if self.liquidity == 'M' else 
                utils.opposite_side(self.side))

    @property
    def fee(self):
        """Summary
        
        Returns:
            TYPE: Description
        """
        return c.TAKER_ORDER_FEE_FRACTION * self.usd_volume if self.liquidity == 'T' else 0.0

    @classmethod
    def get_array_length(cls):
        """Summary
        
        Returns:
            int: Description
        """
        return len(cls._get_array_represenation(price=0.0, 
                                                side='buy', 
                                                size=0.0, 
                                                time=datetime.min))

    def to_array(self):
        """Provides a Numpy representation of the order that can be consumed
        by a Keras model.
        
        Returns:
            np.ndarray: Description
        """
        return self._get_array_represenation(price=self.price, 
                                             side=self.side, 
                                             size=self.size, 
                                             time=self.time)

    def to_fill_dict(self):
        """Summary
        
        Returns:
            dict[str, any]: Description
        """
        return dict(
            created_at=str(self.time), 
            fee=str(self.fee),
            liquidity=self.liquidity,
            order_id=self.taker_order_id if self.liquidity == 'T' else self.maker_order_id,
            price=str(self.price),
            product_id=self.product_id,
            settled=True,
            side=self.account_order_side,
            size=str(self.size),
            trade_id=self.trade_id, 
            usd_volume=self.usd_volume)

    @property
    def usd_volume(self):
        """Summary
        
        Returns:
            float: Description
        """
        return self.price * self.size


class CoinbaseOrder(Base): #pylint: disable=R0903,R0902

    """Model for Coinbase orders
    
    Attributes:
        client_oid (Column[String]): ID given by order submitter
        done_at (datetime): Description
        done_reason (bool): Description
        funds (Column[Float]): Amount of funds available for trading for 
            market orders.
        order_id (Column[String]): ID given to order by Coinbase
        order_status (Column[String]): Can be 'received', 'open', or 'closed'. 
            In this case we're only storing 'received' events
        order_type (Column[String]): Can be 'limit' or 'market'
        post_only (bool): Description
        price (Column[Float]): Price given to each unit of currency
        product_id (Column[String]): Type of product (e.g. BTC-USD)
        reject_reason (str): Description
        row_id (Column[BigInteger]): Primary key
        sequence (Column[String]): String identifier given by Coinbase 
            websocket stream
        side (Column[String]): 'buy' or 'sell'
        size (Column[Float]): Amount of currency to be purchased for limit 
            orders
        time (Column[DateTime]): Datetime order was received
        time_in_force (str): Description
    """
    
    __tablename__ = 'coinbase_orders'

    client_oid = Column(String)
    funds = Column(Float)
    order_id = Column(String)
    order_type = Column(String)
    order_status = Column(String)
    price = Column(Float)
    product_id = Column(String)
    row_id = Column(BigInteger, primary_key=True, autoincrement=True)
    sequence = Column(BigInteger)
    side = Column(String)
    size = Column(Float)
    time = Column(DateTime)

    def __init__(self, post_only=None, time_in_force=None, **kwargs):
        """Summary
        
        Args:
            post_only (TYPE): Description
            time_in_force (TYPE): Description
            **kwargs: Description
        """
        self.done_at = None
        self.done_reason = None
        self.matches = []
        self.post_only = post_only
        self.reject_reason = None
        self.remaining_size = kwargs.get('size')
        self.time_in_force = time_in_force

        super().__init__(**kwargs)

    @staticmethod
    def _get_array_represenation(price, funds, order_type, side, size, time):
        """Summary
        
        Args:
            price (float): Description
            funds (float): Description
            order_type (str): Description
            side (str): Description
            size (float): Description
            time (datetime): Description
        
        Returns:
            np.ndarray: Description
        """
        _array = ([price, size, funds] + 
                  utils.one_hot_encode_order_side(side) + 
                  utils.one_hot_encode_order_type(order_type) + 
                  utils.preprocess_time(time)
                  )

        return np.array(_array)

    @reconstructor
    def init_on_load(self):
        """Summary
        """
        self.remaining_size = self.size

    def open_order(self):
        """Summary
        """
        self.order_status = 'open'

    def close_order(self, done_at, done_reason):
        """Summary
        
        Args:
            done_at (TYPE): Description
            done_reason (TYPE): Description
        """
        self.order_status = 'done'
        self.done_at = done_at
        self.done_reason = done_reason

    @property
    def executed_value(self):
        """Summary
        
        Returns:
            float: Description
        """
        return sum([match.usd_volume for match in self.matches])

    @property
    def fill_fees(self):
        """Summary
        
        Returns:
            float: Description
        """
        return sum([match.fee for match in self.matches])

    @property
    def filled_size(self):
        """Summary
        
        Returns:
            float: Description
        """
        return sum([match.size for match in self.matches])

    @classmethod
    def get_array_length(cls):
        """Summary
        
        Returns:
            int: Description
        """
        return len(cls._get_array_represenation(price=0.0, 
                                                funds=0.0, 
                                                order_type='limit', 
                                                side='buy', 
                                                size=0.0, 
                                                time=datetime.min))

    def reject_order(self, reject_reason):
        """Summary
        
        Args:
            reject_reason (str): Description
        """
        self.order_status = 'rejected'
        self.reject_reason = reject_reason

    def to_array(self):
        """Provides a Numpy representation of the order that can be consumed
        by a Keras model.
        
        Returns:
            np.ndarray: Description
        """
        price = self.price if self.price is not None else 0.0
        size = self.size if self.size is not None else 0.0
        funds = self.funds if self.funds is not None else 0.0

        return self._get_array_represenation(price=price, 
                                             funds=funds, 
                                             order_type=self.order_type, 
                                             side=self.side, 
                                             size=size, 
                                             time=self.time)

    def to_dict(self):
        """This representation gets sent to MockAuthenticatedClient 
        when it requests information about orders.
        
        Returns:
            dict: Description
        """
        return dict(
            created_at=str(self.time),
            executed_value=str(self.executed_value),
            fill_fees=str(self.fill_fees),
            filled_size=str(self.filled_size),
            id=self.order_id, 
            post_only=self.post_only,
            price=str(self.price),
            product_id=self.product_id,
            reject_reason=self.reject_reason,
            settled=False,
            side=self.side,
            size=str(self.size),
            status=self.order_status,
            time_in_force=self.time_in_force,
            type=self.order_type)

if __name__ == '__main__':
    if not database_exists(c.ENGINE.url):
        create_database(c.ENGINE.url)

    Base.metadata.create_all(c.ENGINE)
