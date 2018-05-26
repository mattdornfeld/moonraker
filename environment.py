from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from rl.core import Env
import random
from datetime import timedelta
import numpy as np
from datetime import datetime
import uuid
from itertools import product
from queue import PriorityQueue
from multiprocessing import Process, Queue

from gdax_train.constants import *
from gdax_train.orm import GDAXTrade
from gdax_train.utils import *

class Order:

    def __init__(self, price, size, product_id, received_dt, order_side, order_status):
        """
        price : float

        size : float

        product_id : str

        received_dt : datetime.datetime

        orde_side : str
            'buy' or 'sell'

        order_status : str
            'received', 'open', 'match'
        """

        self.price = price
        self.size = size
        self.product_id = product_id
        self.received_dt = received_dt
        self.matched_dt = None
        self.order_side = order_side
        self.order_id = uuid.uuid4().__str__()
        self.order_status = order_status

class Wallet:

    def __init__(self, initital_product_amount, initial_usd):

        self.product_amount = initital_product_amount
        self.usd = initial_usd
        self.orders = []

    def buy(self, price, size, product_id):

        buy_order = Order(
            price = price,
            size = size,
            product_id = product_id,
            received_dt = datetime.now(),
            order_side = 'buy',
            order_status ='open')

        self.orders.append( buy_order )


    def sell(self, price, size, product_id):

        sell_order = Order(
            price = price,
            size = size,
            product_id = product_id,
            received_dt = datetime.now(),
            order_side = 'sell',
            order_status = 'open')

        self.orders.append( sell_order )


    def order_match(self, order_id, order_side, matched_dt):

        for order in self.get_orders(
            order_side = order_side,
            order_status = 'open'):

            if order.order_id == order_id:
        
                order.order_status = 'match'
                
                order.matched_dt = matched_dt

                order.wallet_matched_dt = datetime.now()

                if order_side == 'buy':

                    self.usd -= order.price * order.size

                    self.product_amount += order.size

                elif order_side == 'sell':

                    self.usd += order.price * order.size

                    self.product_amount -= order.size


    def get_orders(self, order_side=None, order_status=None):

        if order_side is None:
            order_side = ORDER_SIDES
        elif isinstance(order_side, str):
            order_side = [order_side]
        elif isinstance(order_side, list):
            order_side = order_side
        else:
            raise TypeError('order_side must be str or List[str]')

        if order_status is None:
            order_status = EVENT_TYPES
        elif isinstance(order_status, str):
            order_status = [order_status]
        elif isinstance(order_status, list):
            order_status = order_status
        else:
            raise TypeError('order_status must be str or List[str]')

        return [order for order 
        in self.orders if order.order_side in order_side
        and order.order_status in order_status]


    @property
    def buy_orders(self):

        return [order for order in self.orders if order.order_side=='buy']


    @property
    def sell_orders(self):

        return [order for order in self.orders if order.order_side=='sell']


class ExchangeStateWorker(Process):

    def __init__(self, dt_queue, exchange_state_queue, 
        time_delta, sequence_length):

        self.dt_queue = dt_queue

        self.exchange_state_queue = exchange_state_queue

        self.time_delta = time_delta

        self.sequence_length = sequence_length

        self.engine = create_engine(
            'postgresql://{}:{}@{}/{}'.format(
            POSTGRES_USERNAME, POSTGRES_PASSWORD, DB_HOST, DB_NAME))

        self.Session = sessionmaker(bind = self.engine)

        super().__init__()


    def run(self):

        while True:

            start_dt = self.dt_queue.get()

            sequence_of_events = self._get_sequence_of_events(start_dt)

            self.exchange_state_queue.put((start_dt, sequence_of_events))

    def _pad_events(self, sequence_of_events):

         most_events = max([events.shape[1] for events in sequence_of_events])

         for i, events in enumerate(sequence_of_events):

            num_events = events.shape[1]

            if num_events < most_events:

                padding = generate_padding_vector( 
                    num_events_per_time_step = most_events - num_events,
                    sequence_length = 1)

                events = np.append( events, padding, axis = 1 )

            sequence_of_events[i] = events

         return sequence_of_events

    def _query_events_from_db(self, start_dt, end_dt, event_type, order_side):

        sess = self.Session()

        _events = (
            sess
            .query(GDAXTrade)
            .filter( GDAXTrade.time.between(start_dt, end_dt) )
            .filter(GDAXTrade.event_type == event_type)
            .filter(GDAXTrade.side == order_side)
            .all() )

        sess.close()

        return _events

    def _get_events_of_type(self, start_dt, end_dt, event_type, order_side):

        _events = self._query_events_from_db( 
            start_dt = start_dt, 
            end_dt = end_dt, 
            event_type = event_type, 
            order_side = order_side)

        event_state_vectors = []
        for event in _events:

            if event.price is None: continue

            if event.size is not None:
                event_size = event.size
            else:
                event_size = event.remaining_size
            
            event_state_vector = generate_state_vector(
                event_price = event.price,
                event_size = event_size,
                event_type = event.event_type,
                event_side = event.side,
                event_time = event.time,
                order_id = event.order_id
                )

            event_state_vectors.append( event_state_vector )

        return event_state_vectors

    def _get_events(self, start_dt, end_dt):

        events = []

        for event_type, order_side in product(EVENT_TYPES, ORDER_SIDES):

            events_of_type = self._get_events_of_type(
                start_dt, end_dt, event_type, order_side)

            if len(events_of_type ) > 0:
                events.append( events_of_type )

        if len(events) == 0:
            events = generate_padding_vector(num_events_per_time_step=1)
        else:
            events = np.vstack(events)

        return events

    def _get_sequence_of_events(self, start_dt):
        sequence_of_events = []

        for n in range(self.sequence_length):

            events = self._get_events(
                start_dt = start_dt + n* self.time_delta,
                end_dt = start_dt + (n+1) * self.time_delta )

            sequence_of_events.append( events )

        sequence_of_events = [np.expand_dims(events, axis = 0) 
        for events in sequence_of_events]

        sequence_of_events = self._pad_events(sequence_of_events)

        sequence_of_events = np.vstack(sequence_of_events)

        return sequence_of_events

class MockExchange(Env):

    def __init__(self, 
        wallet,
        start_dt,
        end_dt, 
        time_delta, 
        sequence_length, 
        num_workers,
        buffer_size):

        self.wallet = wallet

        #configs
        self.buffer_size = buffer_size
        self.start_dt = start_dt
        self.end_dt = end_dt
        self.time_delta = time_delta
        self.sequence_length = sequence_length
        
        #Data queues
        # self.dt_queue = generate_datetime_queue(start_dt, end_dt, time_delta)
        # self.exchange_state_queue = create_multiprocess_priority_queue(
        #     maxsize=buffer_size)
        self._reset_queues()

        #Multiprocessing workers
        self.workers = []
        for _ in range(num_workers):
            
            worker = ExchangeStateWorker(
                dt_queue = self.dt_queue, 
                exchange_state_queue = self.exchange_state_queue, 
                time_delta = self.time_delta, 
                sequence_length = self.sequence_length)

            worker.daemon = True
            worker.start()
            self.workers.append(worker)

    def __del__(self):

        for worker in self.workers:
            worker.terminate()


    def _make_transactions(self, action):

        price, size = action

        #If the algorithm tries to spend more money than it has,
        #sell more coins than it has, or makes a purchase of size=0
        #do nothing
        if ( (self.wallet.usd - size * price) < 0 or 
            (self.wallet.product_amount - size < 0) or
            size == 0):
            
            return

        if size > 0:

            self.wallet.buy(
                size = size,
                price = price,
                product_id = 'btc-usd')

        else:

            self.wallet.sell(
                size = abs(size),
                price = price,
                product_id = 'btc-usd')

    def _find_matches(self, exchange_state):

        reward = 0
        for order_side, event_side in [('buy' , 'sell'), ('sell', 'buy')]:

            used_order_ids = set()
        
            for t in range(self.sequence_length):

                open_orders = self.wallet.get_orders(
                    order_side = order_side, 
                    order_status = 'open')

                for order in open_orders:

                    query_event_state_vector = generate_state_vector(
                        event_price = order.price,
                        event_size = order.size,
                        event_type = 'open',
                        event_side = event_side,
                        event_time = datetime.now(), 
                        order_id = None
                        )

                    match_idxs = np.where(
                        (exchange_state[t, :, 2:] 
                            == query_event_state_vector[2:]).all(axis = -1) )

                    matched_order_ids = exchange_state[t, match_idxs, 0][0]
                    matched_dts = exchange_state[t, match_idxs, 1][0]

                    for matched_order_id, matched_dt in zip(matched_order_ids, matched_dts):

                        if matched_order_id not in used_order_ids:

                            self.wallet.order_match(
                                order_id = order.order_id,
                                order_side = order_side, 
                                matched_dt = matched_dt)

                            used_order_ids.add(matched_order_id)

                            if order_side == 'buy':
                                reward += order.price * order.size

                            break

        return reward


    def _get_wallet_state(self, start_dt):

        sequence_of_states = []
        for n in range(self.sequence_length):

            order_states = []
            for order in self.wallet.get_orders(order_status = 'open'):
                
                order_state_vector = generate_state_vector(
                    event_price = order.price,
                    event_size = order.size,
                    event_type = 'open',
                    event_side = order.order_side,
                    event_time = order.received_dt, 
                    order_id = order.order_id
                    )

                order_states.append(order_state_vector)

            for order in self.wallet.get_orders(order_status = 'match'):

                if order.matched_dt in DatetimeRange(
                    start_dt = start_dt + n * self.time_delta,
                    end_dt = start_dt + (n+1) * self.time_delta):
                
                    order_state_vector = generate_state_vector(
                        event_price = order.price,
                        event_size = order.size,
                        event_type = 'match',
                        event_side = order.order_side,
                        event_time = order.matched_dt, 
                        order_id = order.order_id
                        )

                    order_states.append(order_state_vector)

            sequence_of_states.append(order_states)

        stacked_states = stack_sequence_of_states(sequence_of_states)

        return stacked_states


    def _is_episode_over(self):
        #Game ends if we run out of money.
        return self.wallet.product_amount <= 0 and self.wallet.usd <= 0 

    def _reset_queues(self):
        if hasattr(self, 'dt_queue'):
            empty_queue(self.dt_queue)
        
        if hasattr(self, 'exchange_state_queue'):
            empty_queue(self.exchange_state_queue)
        else:
            self.exchange_state_queue = create_multiprocess_priority_queue(
                maxsize=self.buffer_size)

        if hasattr(self, 'dt_queue'):
            self.dt_queue = populate_datetime_queue(
                self.start_dt, self.end_dt, self.time_delta, self.dt_queue)
        else:
            self.dt_queue = populate_datetime_queue(
                self.start_dt, self.end_dt, self.time_delta)

    def step(self, action):

        start_dt, exchange_state = self.exchange_state_queue.get()

        reward = self._find_matches(exchange_state)

        self._make_transactions(action)

        wallet_state = self._get_wallet_state(start_dt)

        state = [exchange_state[:,:,2:], wallet_state[:,:,2:]]

        is_done = self._is_episode_over()

        info_dict = {}

        return state, reward, is_done, info_dict

    def reset(self):

        self._reset_queues()

        start_dt, exchange_state = self.exchange_state_queue.get()
        wallet_state = self._get_wallet_state(start_dt)
        state = [exchange_state[:,:,2:], wallet_state[:,:,2:]]

        return state


if __name__ == '__main__':

    env = MockExchange( 
        wallet = Wallet(initital_product_amount = 0, initial_usd = 1000),
        start_dt = datetime.now(),
        end_dt = datetime.now() + 1000*timedelta(seconds=600),
        time_delta = timedelta(seconds=600),
        sequence_length = 3,
        episode_length = 10,
        num_workers = 3,
        buffer_size = 100)

    state, reward, _, _ = env.step([1.1, 1.0])

    state, reward, _, _ = env.step([1.2, 1.0])

    state, reward, _, _ = env.step([1.3, -1.0])

    state, reward, _, _ = env.step([1.4, 1.0])
