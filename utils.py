from multiprocessing import Queue
from multiprocessing.managers import SyncManager
from queue import PriorityQueue
import random
from rl.core import Processor

from gdax_train.constants import *

def generate_padding_vector(num_events_per_time_step, sequence_length = None):

	if sequence_length is None:
		padding = np.zeros((num_events_per_time_step, 
			NUM_EXCHANGE_FEATURES + NUM_METADATA_FEATURES)).astype('object')

		padding[:,0] = NULL_ID
		padding[:,1] = NULL_DATETIME

	else:
		padding = np.zeros((sequence_length, num_events_per_time_step, 
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

class MultiInputProcessor(Processor):
    def __init__(self):
        pass

    def _calc_largest_number_of_events(self, state_batch):
        num_branches = len(state_batch[0][0])
        most_events = [0 for _ in range(num_branches)]
        for state in state_batch:
            for i, branch in enumerate(state[0]):
                num_events = branch.shape[1]
                if num_events > most_events[i]:
                    most_events[i] = num_events

        return most_events

    def _pad_state(self, branch_state, most_event):
        num_event = branch_state.shape[1]
        
        if num_event < most_event:
            padding = np.zeros(
                (branch_state.shape[0], 
                 most_event - num_event, 
                 branch_state.shape[2]) )

            branch_state = np.append(branch_state, padding, axis=1)

        return branch_state


    def process_state_batch(self, state_batch):
        most_events = self._calc_largest_number_of_events(state_batch)

        branched_state_batches = [[] for _ in range(len(most_events))]
        for state in state_batch:
            for i, branch_state in enumerate(state[0]):
                branch_state = self._pad_state(branch_state, most_events[i])
                branch_state = np.expand_dims(branch_state, axis=0)
                branched_state_batches[i].append(branch_state)

        branched_state_batches = list(map(np.vstack, branched_state_batches))

        return branched_state_batches