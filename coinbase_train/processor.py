"""Summary
"""
import numpy as np

from lib.rl.core import Processor

class CoibaseEnvironmentProcessor(Processor):

    """This class takes output state from Environment.step
    and converts it to a form that can be processed by the
    actor and critic models.
    """
    
    @staticmethod
    def _calc_largest_number_of_events(state_batch):
        """Summary
        
        Args:
            state_batch (List[np.ndarray]): Description
        
        Returns:
            List[int]: Description
        """
        num_branches = len(state_batch[0][0])
        most_events = [0 for _ in range(num_branches)]
        for state in state_batch:
            for i, branch in enumerate(state[0]):
                num_events = branch.shape[1]
                if num_events > most_events[i]:
                    most_events[i] = num_events

        return most_events

    @staticmethod
    def _pad_state(branch_state, most_event):
        """Summary
        
        Args:
            branch_state (np.ndarray): Description
            most_event (int): Description
        
        Returns:
            np.ndarray: Description
        """
        num_event = branch_state.shape[1]
        
        if num_event < most_event:
            shape = (branch_state.shape[0], most_event - num_event, branch_state.shape[2])
            padding = np.zeros(shape)

            branch_state = np.append(branch_state, padding, axis=1)

        return branch_state


    def process_state_batch(self, batch):
        """Summary
        
        Args:
            batch (List[List[np.ndarray]]): Description
        
        Returns:
            List[np.ndarray]: Description
        """
        most_events = self._calc_largest_number_of_events(batch)

        branched_state_batches = [[] for _ in range(len(most_events))]
        for state in batch:
            for i, branch_state in enumerate(state[0]):
                _branch_state = self._pad_state(branch_state, most_events[i])
                branch_state = np.expand_dims(_branch_state, axis=0)
                branched_state_batches[i].append(branch_state)

        branched_state_batches = [np.vstack(bsb) for bsb in branched_state_batches]

        return branched_state_batches
