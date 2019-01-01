# -*- coding: utf-8 -*-
from __future__ import absolute_import

import copy
from keras.engine import Layer
from keras.engine import InputSpec
from keras.engine.topology import _object_list_uid
from keras.utils.generic_utils import has_arg
from keras import backend as K
from keras.layers import Wrapper


class TimeDistributed(Wrapper):
    """This wrapper applies a layer to every temporal slice of an input.

    The input should be at least 3D, and the dimension of index one
    will be considered to be the temporal dimension.

    Consider a batch of 32 samples,
    where each sample is a sequence of 10 vectors of 16 dimensions.
    The batch input shape of the layer is then `(32, 10, 16)`,
    and the `input_shape`, not including the samples dimension, is `(10, 16)`.

    You can then use `TimeDistributed` to apply a `Dense` layer
    to each of the 10 timesteps, independently:

    ```python
        # as the first layer in a model
        model = Sequential()
        model.add(TimeDistributed(Dense(8), input_shape=(10, 16)))
        # now model.output_shape == (None, 10, 8)
    ```

    The output will then have shape `(32, 10, 8)`.

    In subsequent layers, there is no need for the `input_shape`:

    ```python
        model.add(TimeDistributed(Dense(32)))
        # now model.output_shape == (None, 10, 32)
    ```

    The output will then have shape `(32, 10, 32)`.

    `TimeDistributed` can be used with arbitrary layers, not just `Dense`,
    for instance with a `Conv2D` layer:

    ```python
        model = Sequential()
        model.add(TimeDistributed(Conv2D(64, (3, 3)),
                                  input_shape=(10, 299, 299, 3)))
    ```

    # Arguments
        layer: a layer instance.
    """

    def __init__(self, layer, **kwargs):
        super(TimeDistributed, self).__init__(layer, **kwargs)
        self.supports_masking = True

    def build(self, input_shape):
        assert len(input_shape) >= 3
        self.input_spec = InputSpec(shape=input_shape)
        child_input_shape = (input_shape[0],) + input_shape[2:]
        if not self.layer.built:
            self.layer.build(child_input_shape)
            self.layer.built = True
        super(TimeDistributed, self).build()

    def compute_output_shape(self, input_shape):
        child_input_shape = (input_shape[0],) + input_shape[2:]
        child_output_shape = self.layer.compute_output_shape(child_input_shape)
        timesteps = input_shape[1]
        return (child_output_shape[0], timesteps) + child_output_shape[1:]

    def call(self, inputs, training=None, mask=None):
        kwargs = {}
        if has_arg(self.layer.call, 'training'):
            kwargs['training'] = training
        uses_learning_phase = False

        input_shape = K.int_shape(inputs)
        if not input_shape[0]:
            # batch size matters, use rnn-based implementation
            def step(x, _):
                global uses_learning_phase
                output = self.layer.call(x, **kwargs)
                if hasattr(output, '_uses_learning_phase'):
                    uses_learning_phase = (output._uses_learning_phase or
                                           uses_learning_phase)
                return output, []

            _, outputs, _ = K.rnn(step, inputs,
                                  initial_states=[],
                                  input_length=input_shape[1],
                                  unroll=False)
            y = outputs
        else:
            # No batch size specified, therefore the layer will be able
            # to process batches of any size.
            # We can go with reshape-based implementation for performance.
            input_length = input_shape[1]
            if not input_length:
                input_length = K.shape(inputs)[1]
            # Shape: (num_samples * timesteps, ...). And track the
            # transformation in self._input_map.
            input_uid = _object_list_uid(inputs)
           
            #Added this line in so that this wrapper works with layers where some of the
            #dimensions have length None 
            new_input_shape = [-1] + [i if i is not None else -1 for i in input_shape[2:]]
            inputs = K.reshape(inputs, new_input_shape)
            self._input_map[input_uid] = inputs
            # (num_samples * timesteps, ...)
            y = self.layer.call(inputs, **kwargs)
            if hasattr(y, '_uses_learning_phase'):
                uses_learning_phase = y._uses_learning_phase
            # Shape: (num_samples, timesteps, ...)
            output_shape = self.compute_output_shape(input_shape)
            
            new_output_shape = [-1, input_length] + [i if i is not None else -1 for i in output_shape[2:]]
            
            y = K.reshape(y, new_output_shape)

        # Apply activity regularizer if any:
        if (hasattr(self.layer, 'activity_regularizer') and
           self.layer.activity_regularizer is not None):
            regularization_loss = self.layer.activity_regularizer(y)
            self.add_loss(regularization_loss, inputs)

        if uses_learning_phase:
            y._uses_learning_phase = True
        return y
