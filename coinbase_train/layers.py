"""Summary
"""
from keras.layers import (Add, Bidirectional, Conv1D, Conv2D, Dense, Layer, 
                          MaxPooling1D, Multiply, TimeDistributed)
from phased_lstm_keras.PhasedLSTM import PhasedLSTM as RNNCell
import tensorflow as tf

class AtrousConvolutionBlock:
    """Summary

    Attributes:
        dilation_depth (TYPE): Description
        nb_filters (TYPE): Description
        nb_stacks (TYPE): Description
        use_skip_connections (TYPE): Description
    """
    def __init__(
            self, 
            causal,
            dilation_depth, 
            nb_filters, 
            nb_stacks, 
            time_distributed, 
            use_skip_connections):
        """Summary
        
        Args:
            causal (TYPE): Description
            dilation_depth (TYPE): Description
            nb_filters (TYPE): Description
            nb_stacks (TYPE): Description
            time_distributed (TYPE): Description
            use_skip_connections (TYPE): Description
        """
        self.dilation_depth = dilation_depth
        self.nb_filters = nb_filters
        self.nb_stacks = nb_stacks
        self.padding = 'causal' if causal else 'same'
        self.time_distributed = time_distributed
        self.use_skip_connections = use_skip_connections
        self.Conv1D = TDConv1D if time_distributed else Conv1D #pylint: disable=C0103


    def __call__(self, input_tensor):
        """Summary
        
        Args:
            input_tensor (tensorflow.Tensor): Description
        
        Returns:
            tensorflow.Tensor: Description
        """
        skip_connections = []

        _output = self.Conv1D(
            dilation_rate=1, 
            filters=self.nb_filters, 
            kernel_size=2, 
            padding=self.padding,
            use_bias=True)(input_tensor)

        for _ in range(self.nb_stacks):
            for i in range(0, self.dilation_depth + 1):
                
                _output, skip_output = self._create_residual_block(
                    dilation=2**i, 
                    input_tensor=_output, 
                    nb_filters=self.nb_filters)
                
                skip_connections.append(skip_output)

        return Add()(skip_connections) if self.use_skip_connections else _output

    def _create_residual_block(self, dilation, input_tensor, nb_filters):
        """Summary
        
        Args:
            dilation (int): Description
            input_tensor (tf.Tensor): Description
            nb_filters (int): Description
        
        Returns:
            Tuple[tf.Tensor, tf.Tensor]: Description
        """
        original_input_tensor = input_tensor

        tanh_out = self.Conv1D(
            activation='tanh',
            dilation_rate=dilation, 
            filters=nb_filters, 
            kernel_size=2, 
            padding=self.padding,
            use_bias=True)(input_tensor)

        sigmoid_out = self.Conv1D(
            activation='sigmoid',
            dilation_rate=dilation, 
            filters=nb_filters, 
            kernel_size=2, 
            padding=self.padding,
            use_bias=True)(input_tensor)

        gated_tensor = Multiply()([tanh_out, sigmoid_out])

        _resisudal = self.Conv1D(
            filters=nb_filters, 
            kernel_size=1, 
            padding='same',
            use_bias=True)(gated_tensor)

        skip = self.Conv1D(
            filters=nb_filters, 
            kernel_size=1, 
            padding='same',
            use_bias=True)(gated_tensor)

        resisudal = Add()([original_input_tensor, _resisudal])

        return resisudal, skip

class Attention(Layer):

    """Summary
    
    Attributes:
        attention_dim (int): Description
        b_w (tf.Tensor, optional): Description
        u_w (tf.Tensor, optional): Description
        W_w (tf.Tensor, optional): Description
    """
    
    def __init__(self, attention_dim):
        """Summary
        
        Args:
            attention_dim (int): Description
        """
        self.attention_dim = attention_dim
        self.W_w = None #pylint: disable=C0103
        self.b_w = None
        self.u_w = None

        super().__init__()

    @staticmethod
    def _matmul(W, h): #pylint: disable=C0103
        """Summary
        
        Args:
            W (tf.Tensor): Description
            h (tf.Tensor): Description
        
        Returns:
            TYPE: Description
        """
        return tf.reduce_sum(tf.expand_dims(h, axis=-2) * W, axis=-1)

    def build(self, input_shape):
        """Summary
        
        Args:
            input_shape (Tuple[int]): Description
        """
        self.W_w = self.add_weight(
            name='W_w', 
            shape=(self.attention_dim, input_shape[-1]),
            initializer=tf.random_normal,
            trainable=True).value()

        self.b_w = self.add_weight(
            name='b_w', 
            shape=(self.attention_dim,),
            initializer=tf.random_normal,
            trainable=True).value()

        self.u_w = self.add_weight(
            name='u_w', 
            shape=(self.attention_dim, ),
            initializer=tf.random_normal,
            trainable=True).value()

        super().build(input_shape)

    def call(self, h): #pylint: disable=W0221
        """Summary
        
        Args:
            h (tf.Tensor): Description
        
        Returns:
            tf.Tensor: Description
        """
        u = tf.tanh(self._matmul(self.W_w, h) + self.b_w) #pylint: disable=C0103

        numerator = tf.reduce_sum(self.u_w * u, axis=-1)

        denominator = tf.reduce_sum(tf.exp(numerator), axis=-1) 

        alpha = tf.exp(numerator) / tf.expand_dims(denominator, axis=-1)

        s = tf.reduce_sum(tf.expand_dims(alpha, axis=-1) * h, axis=-2) #pylint: disable=C0103

        return s

    def compute_output_shape(self, input_shape):
        """Summary
        
        Args:
            input_shape (Tuple[int]): Description
        
        Returns:
            Tuple[int]: Description
        """
        return (*input_shape[0:-2], input_shape[-1])

    def get_config(self):
        """Summary
        
        Returns:
            dict[str, int]: Description
        """
        return {'attention_dim' : self.attention_dim}

def BidirectionalRNN(*args, **kwargs): #pylint: disable=C0103
    """Summary
    
    Args:
        *args: Description
        **kwargs: Description
    
    Returns:
        Bidirectional: Description
    """
    return Bidirectional(RNNCell(
        return_sequences=True, go_backwards=True, *args, **kwargs))

def TDBidirectionalRNN(*args, **kwargs): #pylint: disable=C0103
    """Summary
    
    Args:
        *args: Description
        **kwargs: Description
    
    Returns:
        TimeDistributed: Description
    """
    return TimeDistributed(BidirectionalRNN(*args, **kwargs))

def TDConv1D(*args, **kwargs): #pylint: disable=C0103
    """Summary
    
    Args:
        *args: Description
        **kwargs: Description
    
    Returns:
        TimeDistributed: Description
    """
    return TimeDistributed(Conv1D(*args, **kwargs))

def TDConv2D(*args, **kwargs): #pylint: disable=C0103
    """Summary
    
    Args:
        *args: Description
        **kwargs: Description
    
    Returns:
        TimeDistributed: Description
    """
    return TimeDistributed(Conv2D(*args, **kwargs))

def TDDense(*args, **kwargs): #pylint: disable=C0103
    """Summary
    
    Args:
        *args: Description
        **kwargs: Description
    
    Returns:
        TimeDistributed: Description
    """
    return TimeDistributed(Dense(*args, **kwargs))

def TDMaxPooling1D(*args, **kwargs): #pylint: disable=C0103
    """Summary
    
    Args:
        *args: Description
        **kwargs: Description
    
    Returns:
        TimeDistributed: Description
    """
    return TimeDistributed(MaxPooling1D(*args, **kwargs))
