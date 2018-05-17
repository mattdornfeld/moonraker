from keras import backend as K

def mean_q(y_true, y_pred):
    return K.mean(K.max(y_pred, axis=-1))