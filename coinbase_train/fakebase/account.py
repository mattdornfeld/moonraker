"""Summary
"""
from uuid import uuid4

import numpy as np

from coinbase_train.fakebase import constants as c
from coinbase_train.fakebase.orm import CoinbaseOrder
from coinbase_train.fakebase import utils

class Account:

    """Summary
    
    Attributes:
        funds (dict): Description
        id (TYPE): Description
        orders (dict): Description
    """
    
    def __init__(self): #pylint: disable=W0621
        """Summary
        """
        self.profile_id = str(uuid4())
        self.funds = {}
        self.orders = {}

        for currency in ['USD', 'BTC']:
            self.funds[currency] = dict(id=str(uuid4()), balance=0.0, holds=0.0)

    def _get_funds_as_array(self):
        """Summary
        
        Returns:
            np.ndarray: Description
        """
        _funds_as_array = np.hstack([[fund['balance'], fund['holds']] 
                                     for fund in self.funds.values()])
        return np.expand_dims(_funds_as_array, axis=0)

    def _get_orders_as_array(self):
        """Summary
        """
        list_of_arrays = [order.to_array() for order in self.orders.values() 
                          if order.order_status in ['received', 'open']]

        return utils.vstack(list_of_arrays=list_of_arrays,
                            width=CoinbaseOrder.get_array_length())

    def _remove_holds(self, order):
        """Summary
        
        Args:
            order (CoinbaseOrder): Description
        """
        product_currency, fiat_currency = order.product_id.split('-')

        if order.side == 'buy':
            self.funds[fiat_currency]['holds'] -= order.price * order.size
        else:
            self.funds[product_currency]['holds'] -= order.size

    def add_funds(self, currency, amount):
        """Summary
        
        Args:
            currency (str): Description
            amount (float): Description
        """
        self.funds[currency]['balance'] += amount

    def add_match(self, match, order_id):
        """Summary
        
        Args:
            match (CoinbaseMatch): Description
            order_id (str): Description
        """
        order_side = match.account_order_side

        product_currency, fiat_currency = match.product_id.split('-')

        if order_side == 'buy':
            self.funds[fiat_currency]['balance'] -= match.size * match.price + match.fee
            self.funds[product_currency]['balance'] += match.size
        else:
            self.funds[fiat_currency]['balance'] += match.size * match.price - match.fee
            self.funds[product_currency]['balance'] -= match.size

        self.orders[order_id].matches.append(match)

    def add_order(self, order):
        """Summary
        
        Args:
            order (CoinbaseOrder): Description
        """
        

        product_currency, fiat_currency = order.product_id.split('-')
        
        if order.side == 'buy':
            
            fiat_volume = order.price * order.size * (1 + c.TAKER_ORDER_FEE_FRACTION)
            
            if fiat_volume > self.get_available_funds(fiat_currency):
            
                order.reject_order(reject_reason='insufficient_funds')
            
            if order.order_status == 'received':
                self.funds[fiat_currency]['holds'] += fiat_volume

        else:

            if order.size > self.get_available_funds(product_currency):
                
                order.reject_order(reject_reason='insufficient_funds')

            if order.order_status == 'received':
                self.funds[product_currency]['holds'] += order.size

        self.orders[order.order_id] = order

    def cancel_order(self, order_id):
        """Summary
        
        Args:
            order_id (str): Description

        Raises:
            ValueError: Description
        """
        if self.orders[order_id].order_status != 'open':
            raise ValueError('Can only cancel open order.')

        order = self.orders.pop(order_id)
        self._remove_holds(order)

        return order
        

    def close_order(self, order_id, time):
        """Summary
        
        Args:
            order_id (str): Description
            time (str): Description
        """
        order = self.orders[order_id]

        if order.order_status != 'open':
            raise ValueError('Can only close open or received order.')

        self._remove_holds(order)
        order.close_order(done_at=time, done_reason='filled')

    def get_available_funds(self, currency):
        """Summary
        
        Args:
            currency (str): Description
        
        Returns:
            float: Description
        """
        return self.funds[currency]['balance'] - self.funds[currency]['holds']

    def open_order(self, order_id):
        """Summary
        
        Args:
            order_id (str): Description
        """
        order = self.orders[order_id]

        if order.side == 'buy':

            _, fiat_currency = order.product_id.split('-')

            self.funds[fiat_currency]['holds'] -= (
                order.price * order.size * c.TAKER_ORDER_FEE_FRACTION
                )
        
        order.open_order()

    def get_account_state_as_array(self):
        """Summary
        """
        return [self._get_orders_as_array(), self._get_funds_as_array()]
    
    def to_dict(self):
        """Summary
        
        Returns:
            Dict[str, any]: Description
        """
        funds_dicts = []
        for currency, fund in self.funds.items():
            
            balance = fund['balance']
            holds = fund['holds']
            available = balance - holds
            
            funds_dicts.append({ 
                'available': str(available),
                'balance': str(balance),
                'currency': currency, 
                'holds': str(holds),
                'id': fund['id'],
                'profile_id': self.profile_id
                })
        
        return funds_dicts
