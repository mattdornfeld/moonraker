import random
from multiprocessing import Queue
from multiprocessing.managers import SyncManager
from queue import PriorityQueue
from gdax_train.constants import *

def generate_padding_vector(num_events_per_time_step, num_time_steps = None):

	if num_time_steps is None:
		padding = np.zeros((num_events_per_time_step, 
			NUM_EXCHANGE_FEATURES + NUM_METADATA_FEATURES)).astype('object')

		padding[:,0] = NULL_ID
		padding[:,1] = NULL_DATETIME

	else:
		padding = np.zeros((num_time_steps, num_events_per_time_step, 
			NUM_EXCHANGE_FEATURES + NUM_METADATA_FEATURES)).astype('object')

		padding[:,:,0] = NULL_ID
		padding[:,:,1] = NULL_DATETIME

	return padding


def create_multiprocess_priority_queue(maxsize):

	SyncManager.register('PriorityQueue', PriorityQueue)

	sync_manager = SyncManager()

	sync_manager.start()

	return sync_manager.PriorityQueue(maxsize=maxsize)


def generate_datetime_queue(start_dt, end_dt, time_delta):

    dt_queue = Queue()

    dt = start_dt

    while dt < end_dt:

        dt_queue.put(dt)

        dt += time_delta

    return dt_queue

def generate_state_vector(event_price, event_size, 
    event_type, event_side, event_time, order_id):

    event_type_vector = EVENT_TYPE_VECTORS[event_type]

    order_side_vector = ORDER_SIDE_VECTORS[event_side]

    event_state_vector = np.hstack((
        [order_id],
        [event_time], 
        [event_price], 
        [event_size], 
        event_type_vector, 
        order_side_vector))

    return event_state_vector


def stack_sequence_of_states(sequence_of_states):

    most_states = max([len(state) for state in sequence_of_states])

    if most_states == 0:

        stacked_states = np.zeros((
            NUM_TIME_STEPS,
            1, 
            NUM_EXCHANGE_FEATURES + NUM_METADATA_FEATURES)
        ).astype('object')

        return stacked_states

    else:

        for states in sequence_of_states:

            num_states = len(states)

            for _ in range(most_states - num_states):
                
                padding = np.zeros(
                    NUM_EXCHANGE_FEATURES + NUM_METADATA_FEATURES)

                states.append(padding)

        stacked_states = np.array(sequence_of_states)

        return stacked_states


class DatetimeRange:

    def __init__(self, start_dt, end_dt):

        self.start_dt = start_dt
        self.end_dt = end_dt

    def __contains__(self, dt):

        return (dt > self.start_dt and dt < self.end_dt)