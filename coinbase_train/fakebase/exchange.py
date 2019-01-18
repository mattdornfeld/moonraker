"""Summary

Attributes:
    LOGGER (TYPE): Description
"""
from collections import defaultdict
from datetime import datetime, timedelta
import logging
from math import inf, log
from random import uniform
from uuid import uuid4

import numpy as np

from coinbase_train.fakebase.account import Account
from coinbase_train.fakebase.database_workers import DatabaseWorkers
from coinbase_train.fakebase.orm import CoinbaseMatch, CoinbaseOrder
from coinbase_train.fakebase.utils import vstack, BinaryTree, ExchangeFinishedException

LOGGER = logging.getLogger(__name__)           

class Exchange: #pylint: disable=R0903,R0902

    """Summary
    
    Attributes:
        account (Account): Description
        database_workers (DatabaseWorkers): Description
        end_dt (datetime): Description
        interval_end_dt (datetime): Description
        interval_start_dt (datetime): Description
        matches (List[CoinbaseMatch]): Description
        num_workers (int): Description
        order_book (Dict[str, BinaryTree[Tuple, CoinbaseOrder]]): Description
        received_orders (List): Description
        start_dt (datetime): Description
        time_delta (datetime.timedelta): Description    
    """
    
    def __init__(self, end_dt, num_workers, start_dt, time_delta): #pylint: disable=R0913
        """Summary
        
        Args:
            end_dt (datetime): Description
            num_workers (int): Description
            start_dt (datetime): Description
            time_delta (datetime.timedelta): Description
        """
        self.end_dt = end_dt
        self.num_workers = num_workers
        self.start_dt = start_dt
        self.time_delta = time_delta

        self.interval_start_dt = start_dt
        self.interval_end_dt = start_dt + time_delta

        self.database_workers = DatabaseWorkers(
            end_dt=self.end_dt, 
            num_workers=self.num_workers, 
            start_dt=self.start_dt,
            time_delta=self.time_delta)

        self.account = Account()
        self.matches = []
        self.order_book = dict(buy=BinaryTree(), sell=BinaryTree())
        self.received_orders = []

    def _add_account_orders_to_received_orders_list(self):
        """Summary
        """
        for order in self.account.orders.values():
            if order.order_status == 'received':
                self.received_orders.append(order)

    def _add_order_to_order_book(self, order):
        """Summary
        
        Args:
            order (CoinbaseOrder): Description
        """

        if order.order_id in self.account.orders:
            self.account.open_order(order.order_id)
        else:
            order.open_order()

        if order.side == 'buy':
            self.order_book[order.side].insert(
                key=(order.price, datetime.max - order.time), 
                value=order)
        else:
            self.order_book[order.side].insert(
                key=(order.price, order.time - datetime.min), 
                value=order)

    def _create_match(self, filled_volume, maker_order, taker_order):
        """Summary
        
        Args:
            filled_volume (float): Description
            maker_order (CoinbaseOrder): Description
            taker_order (CoinbaseOrder): Description
        """

        if maker_order.remaining_size == 0:
            if maker_order.order_id in self.account.orders:
                self.account.open_order(maker_order.order_id)
                self.account.close_order(maker_order.order_id, taker_order.time)
            else:
                maker_order.close_order(done_at=taker_order.time, done_reason='filled')

        if taker_order.remaining_size == 0:
            if taker_order.order_id in self.account.orders:
                self.account.open_order(taker_order.order_id)
                self.account.close_order(taker_order.order_id, taker_order.time)
            else:
                taker_order.close_order(done_at=taker_order.time, done_reason='filled')

        if taker_order.order_id in self.account.orders:
            liquidity = 'T'
        elif maker_order.order_id in self.account.orders:
            liquidity = 'M'
        else:
            liquidity = None

        match = CoinbaseMatch(
            liquidity=liquidity,
            maker_order_id=maker_order.order_id,
            price=maker_order.price,
            product_id=maker_order.product_id,
            side=maker_order.side,
            size=filled_volume,
            taker_order_id=taker_order.order_id,
            time=taker_order.time,
            trade_id=str(uuid4())
            )

        self.matches.append(match)

        if maker_order.order_id in self.account.orders:
            self.account.add_match(match, maker_order.order_id)

        if taker_order.order_id in self.account.orders:
            self.account.add_match(match, taker_order.order_id)

    def _check_is_taker(self, price, side):
        """Summary
        
        Args:
            price (float): Description
            side (str): Description

        Returns:
            bool: Description
        """
        return (
            (side == 'buy' and price >= self.min_sell_price) or
            (side == 'sell' and price <= self.max_buy_price)
            )

    def _check_for_self_trade(self, maker_order, taker_order):
        """If self trade will cancel taker and return True. If
        not self trade will return False.
        
        Args:
            maker_order (CoinbaseOrder): Description
            taker_order (CoinbaseOrder): Description
        
        Returns:
            bool: Description
        """
        if (maker_order.order_id in self.account.orders and  #pylint: disable=R1705
                taker_order.order_id in self.account.orders):
            
            self.account.cancel_order(taker_order.order_id)

            return True

        else:

            return False

    def _get_matches_as_array(self):
        """Summary
        
        Returns:
            np.ndarray[float]: Rank 2 tensor. Each row is an order. 
                Each column is a feature.
        """
        list_of_arrays = [match.to_array() for match in self.matches]
        return vstack(list_of_arrays, width=CoinbaseMatch.get_array_length())

    def _get_order_book_as_array(self, order_side, pad_to_length, aggregation=0.01):
        """Summary
        
        Args:
            order_side (str): Description
            pad_to_length (int): Description
            aggregation (float, optional): Description
        
        Returns:
            np.ndarray: Description
        """
    
        def bin_price(price):
            exponent = int(round(log(aggregation) / log(10))) 
            return round(price, -exponent) 

        price_volume_dict = defaultdict(float)
        for _, order in self.order_book[order_side].items():
            price = bin_price(order.price)
            price_volume_dict[price] += order.remaining_size

        _array = (np.array([(price, volume) for price, volume in price_volume_dict.items()])
                  if len(price_volume_dict) > 0 else np.zeros((1, 2)))

        return np.pad(
            array=_array, 
            pad_width=((0, pad_to_length - len(_array)), (0, 0)), 
            mode='constant', 
            constant_values=(0,))

    def _get_received_orders_as_array(self):
        """Summary
        
        Returns:
            np.ndarray[float]: Rank 2 tensor. Each row is an order. 
                Each column is a feature.
        """
        list_of_arrays = [order.to_array() for order in self.received_orders]

        return vstack(list_of_arrays, width=CoinbaseOrder.get_array_length())

    def _match_market_order(self, market_order):  #pylint: disable=R0912
        """If market_order side is 'buy' use funds to purchase product until funds are depleted or 
        no more product is for sale. If market_order side is 'sell' sell specified amount.
        Args:
            market_order (CoinbaseOrder): Description
        """

        if market_order.side == 'buy':
            if market_order.funds is not None and market_order.size is None:
                while market_order.funds > 0:
                    
                    try:
                        _, maker_order = self.order_book['sell'].min_item()
                    except ValueError:
                        #By breaking we effectively cancel the order
                        break

                    if self._check_for_self_trade(maker_order, market_order):
                        break

                    filled_volume = self.deincrement_market_order_funds_and_maker_order_size(
                        maker_order, market_order)

                    self._create_match(filled_volume, maker_order, market_order)

                    if maker_order.remaining_size == 0:
                        self.order_book['sell'].pop_min()

            elif market_order.funds is None and market_order.size is not None:
                while market_order.remaining_size > 0:
                    
                    try:
                        _, maker_order = self.order_book['sell'].min_item()
                    except ValueError:
                        #By breaking we effectively cancel the order
                        break

                    if self._check_for_self_trade(maker_order, market_order):
                        break

                    filled_volume = self.deincrement_matched_order_sizes(maker_order, market_order)

                    self._create_match(filled_volume, maker_order, market_order)

                    if maker_order.remaining_size == 0:
                        self.order_book['sell'].pop_min()

        else:

            #TODO: Think about case where funds and size are specified
            if market_order.funds is not None and market_order.size is None:
                while market_order.funds > 0:
                    
                    try:
                        _, maker_order = self.order_book['buy'].max_item()
                    except ValueError:
                        #By breaking we effectively cancel the order
                        break

                    if self._check_for_self_trade(maker_order, market_order):
                        break

                    filled_volume = self.deincrement_market_order_funds_and_maker_order_size(
                        maker_order, market_order)

                    self._create_match(filled_volume, maker_order, market_order)

                    if maker_order.remaining_size == 0:
                        self.order_book['sell'].pop_max()

            elif market_order.funds is None and market_order.size is not None:
                while market_order.remaining_size > 0:
                    
                    try:
                        _, maker_order = self.order_book['sell'].max_item()
                    except ValueError:
                        #By breaking we effectively cancel the order
                        break

                    if self._check_for_self_trade(maker_order, market_order):
                        break

                    filled_volume = self.deincrement_matched_order_sizes(maker_order, market_order)

                    self._create_match(filled_volume, maker_order, market_order)

                    if maker_order.remaining_size == 0:
                        self.order_book['sell'].pop_max()

    def _match_limit_order(self, taker_order):
        """Matches taker limit order with highest priority maker order
        
        Args:
            taker_order (CoinbaseOrder): Description
        """
        if taker_order.side == 'buy':
            while taker_order.remaining_size > 0:
                
                try:
                    _, maker_order = self.order_book['sell'].min_item()
                except ValueError:
                    #If there are no orders left on the book add order to the order book and break
                    self._add_order_to_order_book(taker_order)
                    break

                if self._check_for_self_trade(maker_order, taker_order):
                    break

                filled_volume = self.deincrement_matched_order_sizes(maker_order, taker_order)

                self._create_match(filled_volume, maker_order, taker_order)

                if maker_order.remaining_size == 0:
                    self.order_book['sell'].pop_min()

        else:

            while taker_order.remaining_size > 0:
                
                try:
                    _, maker_order = self.order_book['buy'].max_item()
                except ValueError:
                    #If there are no orders left on the book add order to the order book and break
                    self._add_order_to_order_book(taker_order)
                    break

                if self._check_for_self_trade(maker_order, taker_order):
                    break
                
                filled_volume = self.deincrement_matched_order_sizes(maker_order, taker_order)

                self._create_match(filled_volume, maker_order, taker_order)

                if maker_order.remaining_size == 0:
                    self.order_book['buy'].pop_max()

    def cancel_order(self, order_id):
        """Summary
        
        Args:
            order_id (str): Description
        """
        order = self.account.cancel_order(order_id)

        if order.side == 'buy':
            self.order_book['buy'].pop(key=(order.price, datetime.max - order.time))
        else:
            self.order_book['sell'].pop(key=(order.price, order.time - datetime.min))

    @staticmethod
    def deincrement_matched_order_sizes(maker_order, taker_order):
        """Simultaneously deincrement the remaining_size of a 
        matched maker and taker order 
        
        Args:
            maker_order (CoinbaseOrder): Description
            taker_order (CoinbaseOrder): Description
        
        Returns:
            float: filled_volume
        """

        filled_volume = min(maker_order.remaining_size, taker_order.remaining_size)

        maker_order.remaining_size, taker_order.remaining_size = (
            max(0, maker_order.remaining_size - taker_order.remaining_size), 
            max(0, taker_order.remaining_size - maker_order.remaining_size))

        return filled_volume

    @staticmethod
    def deincrement_market_order_funds_and_maker_order_size(maker_order, market_order):
        """Summary
        
        Args:
            maker_order (CoinbaseOrder): Description
            market_order (CoinbaseOrder): Description
        
        Returns:
            float: filled_volume
        """
        market_order_desired_volume = market_order.funds / maker_order.price
                                            
        filled_volume = min(market_order_desired_volume, maker_order.remaining_size)

        market_order.funds, maker_order.remaining_size = (
            max(0, market_order.funds - maker_order.price * filled_volume),
            max(0, maker_order.remaining_size - filled_volume)
            )

        return filled_volume 

    def get_exchange_state_as_array(self, pad_order_book_to_length):
        """[matches, received_orders, buy_order_book, sell_order_book, 
        account_orders, account_funds]

        Args:
            pad_order_book_to_length (int): Description

        Returns:
            List[np.ndarray]: Description
        """
        state = [
            self._get_matches_as_array(), 
            self._get_received_orders_as_array(), 
            self._get_order_book_as_array('buy', pad_order_book_to_length),
            self._get_order_book_as_array('sell', pad_order_book_to_length)
            ]
        
        state += self.account.get_account_state_as_array()

        return state

    @property
    def finished(self):
        """Returns True if we reached the end of the training period. 
        False if not. 
        
        Returns:
            bool: Description
        """
       
        return self.interval_end_dt > self.end_dt

    @property
    def max_buy_price(self):
        """Summary
        
        Returns:
            float: Description
        """
        return self.order_book['buy'].max_key_or_default(
                        default_value=(0.0,))[0]

    @property
    def min_sell_price(self):
        """Summary
        
        Returns:
            float: Description
        """
        return self.order_book['sell'].min_key_or_default(
                        default_value=(inf,))[0]

    def place_limit_order(self, product_id, price, side, size, post_only=False): 
        """Summary
        
        Args:
            product_id (str): Description
            price (float): Description
            side (str): Description
            size (float): Description
            post_only (bool, optional): Description
        
        Returns:
            CoinbaseOrder: Description
        """
        order_id = str(uuid4())
        #include random offset so that orders place in the same step aren't placed
        #at the exact same time
        time = self.interval_end_dt + timedelta(seconds=uniform(-0.01, 0.01))

        order = CoinbaseOrder(
            order_id=order_id,
            order_type='limit',
            order_status='received',
            post_only=post_only,
            price=price,
            product_id=product_id,
            side=side,
            size=size,
            time=time,
            time_in_force='gtc')

        if post_only and self._check_is_taker(price, side):
            order.reject_order(reject_reason='post_only')

        self.account.add_order(order)

        return order


    def step(self):
        """Summary
        
        Raises:
            ExchangeFinishedException: Description
        """
        if self.finished:
            raise ExchangeFinishedException

        self.matches = []

        self.interval_start_dt, self.received_orders = self.database_workers.results_queue.get()
        self.interval_end_dt = self.interval_start_dt + self.time_delta

        self._add_account_orders_to_received_orders_list()

        for order in self.received_orders:

            if order.order_type == 'limit':

                if order.side == 'buy':

                    if order.price < self.min_sell_price:
                        self._add_order_to_order_book(order)
                    else:
                        self._match_limit_order(order)

                else:

                    if order.price > self.max_buy_price:
                        self._add_order_to_order_book(order)
                    else:  
                        self._match_limit_order(order)

            else:
                self._match_market_order(order)
