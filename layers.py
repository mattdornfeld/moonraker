from keras.layers import Layer
import tensorflow as tf

class Attention(Layer):

    def __init__(self, attention_dim):

        self.attention_dim = attention_dim

        super().__init__()

    def build(self, input_shape):

        self.W_w = self.add_weight(
            name = 'W_w', 
            shape = (self.attention_dim, input_shape[-1]),
            initializer = tf.random_normal,
            trainable = True).value()

        self.b_w = self.add_weight(
            name = 'b_w', 
            shape = (self.attention_dim,),
            initializer = tf.random_normal,
            trainable = True).value()

        self.u_w = self.add_weight(
            name = 'u_w', 
            shape = (self.attention_dim, ),
            initializer = tf.random_normal,
            trainable = True).value()

        super().build(input_shape)

    def call(self, h):

        u = tf.tanh( self._matmul(self.W_w, h) + self.b_w )

        numerator = tf.reduce_sum(self.u_w * u, axis = -1)

        denominator = tf.reduce_sum(tf.exp(numerator), axis = -1) 

        α = tf.exp(numerator) / tf.expand_dims(denominator, axis = -1)

        s = tf.reduce_sum( tf.expand_dims(α, axis=-1) * h, axis = -2 )

        return s


    def compute_output_shape(self, input_shape):

        return (*input_shape[0:-2], input_shape[-1])


    def get_config(self):

        return {'attention_dim' : self.attention_dim}


    def _matmul(self, W, h):

        return tf.reduce_sum( tf.expand_dims(h, axis=-2) * W , axis=-1)