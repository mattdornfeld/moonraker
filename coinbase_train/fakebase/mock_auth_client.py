"""Summary
"""
from itertools import chain
from uuid import uuid4

from coinbase_train.fakebase import constants as c
from coinbase_train.fakebase.exchange import Exchange
from coinbase_train.fakebase import utils

class MockAuthenticatedClient:

    """Mocks some of the methods of the cbpro AuthenticatedClient
    
    Attributes:
        exchange (Exchange, optional): Description
    
    """
    
    def __init__(self):
        """Summary
        """
        self.exchange = None

    def _check_exchange_is_initiated(self):
        if self.exchange is None:
            raise AttributeError('You must call self.init_exchange before using this method.')

    @staticmethod
    def _check_to_precise(field, value):
        """Summary
        
        Args:
            field (TYPE): Description
            value (TYPE): Description
        
        Returns:
            TYPE: Description
        """
        return utils.get_precision(value) > c.PRECISION[field]

    @staticmethod
    def _check_to_small(field, value):
        """Summary
        
        Args:
            field (TYPE): Description
            value (TYPE): Description
        
        Returns:
            TYPE: Description
        """
        return value < 10**-c.PRECISION[field]


    def cancel_all(self, product_id=c.PRODUCT_ID):
        """Summary
        
        Args:
            product_id (TYPE, optional): Description
        """
        self._check_exchange_is_initiated()

        for order in list(self.exchange.account.orders.values()):
            if order.order_status == 'open' and order.product_id == product_id:
                self.exchange.cancel_order(order.order_id)

    def deposit(self, amount, currency, payment_method_id=None): #pylint: disable=W0613
        """Summary
        
        Args:
            amount (float): Description
            currency (str): Description
            payment_method_id (str, optional): not used
        """
        self._check_exchange_is_initiated()

        self.exchange.account.add_funds(currency, amount)

        return {
            'id': str(uuid4()), 
            'amount': str(amount), 
            'currency': str(currency), 
            'payout_at': str(self.exchange.start_dt)
        }

    def get_accounts(self):
        """Summary
        
        Returns:
            TYPE: Description
        """
        self._check_exchange_is_initiated()

        return self.exchange.account.to_dict()

    def get_order(self, order_id):
        """Summary
        
        Args:
            order_id (str): Description
        
        Returns:
            TYPE: Description
        """
        self._check_exchange_is_initiated()

        return self.exchange.account.orders[order_id].to_dict()

    def get_orders(self, product_id=c.PRODUCT_ID, status=None):
        """Returns all orders of a given status if if status is specified.
        If status is not specified returns all orders for the account.
        
        Args:
            product_id (str): Description
            status (str): Description
        
        Returns:
            Generator[dict[str, any]]: Description
        """
        self._check_exchange_is_initiated()

        statuses = utils.UniversalSet() if status is None else [status]

        return (order.to_dict() 
                for order in self.exchange.account.orders.values() 
                if order.order_status in statuses and
                order.product_id == product_id)

    def get_fills(self, order_id=None, product_id=c.PRODUCT_ID):
        """Returns fills for order if orded_id is specified. If order_id
        is not specified returns all fills for the account.
        
        Args:
            order_id (str, optional): Description
            product_id (str, optional): Description
        
        Returns:
            Genrator[dict[str, any]]: Description
        """
        self._check_exchange_is_initiated()

        gen = (chain.from_iterable(order.matches for order in self.exchange.account.orders.values())
               if order_id is None else 
               self.exchange.account.orders[order_id].matches)

        return (match.to_fill_dict() for match in gen if match.product_id == product_id)

    def init_exchange(self, end_dt, num_workers, start_dt, time_delta):
        """Summary
        
        Args:
            end_dt (datetime.datetime): Description
            num_workers (int): Description
            start_dt (datetime.datetime): Description
            time_delta (datetime.timedelta): Description
        """
        self.exchange = Exchange(
            end_dt=end_dt, 
            num_workers=num_workers, 
            start_dt=start_dt, 
            time_delta=time_delta)

    def place_limit_order(self, product_id, price, side, size, post_only=False):
        """Summary
        
        Args:
            product_id (str): Description
            price (float, optional): Description
            side (str): Description
            size (float): Description
            post_only (bool, optional): Description
        
        Returns:
            Dict[str, any]: Description
        """
        self._check_exchange_is_initiated()

        price = float(price)
        size = float(size)

        for field, value in {'USD': price, c.ACCOUNT_PRODUCT: size}.items():
            if self._check_to_small(field, value) or self._check_to_precise(field, value):
                raise utils.IllegalTransactionException(
                    f'The value {value} provided for {field} is either too small or too precise.')

        return_dict = self.exchange.place_limit_order(
            product_id=product_id, 
            side=side, 
            size=size, 
            post_only=post_only, 
            price=price).to_dict()

        if return_dict['status'] == 'rejected':
            raise utils.IllegalTransactionException(f'Order was rejected. Details {return_dict}.')

        return return_dict
