"""Implements a Gaussian Copula. Actor model outputs mean and covariance for
copula model. Environment then samples from copula to get price and size for limit
orders.
"""
from tensorflow_probability import bijectors as tfb, distributions as tfd

class GaussianCopula(tfd.TransformedDistribution): #pylint: disable=W0223
    """
    F: Standard normal CDF
    F_{mu, sigma}: Normal with mean mu and covariance matrix sigma
    u_i: uniformly distributed random variable
    Gaussian copula is defined as
    C(u_1,u_2,...,u_n)=F_{mu, sigma}(F^{-1}(u_1),F^{-1}(u_2),...,F^{-1}(u_n))
    """
    def __init__(self, mu, sigma_cholesky):
        """Summary
        
        Args:
            mu (tf.Tensor): mean
            sigma_cholesky (tf.Tensor): cholesky decomposition of
                covariance matrix. sigma = sigma_cholesky * sigma_cholesky*.
                Must be lower triangular.
        """
        super().__init__(
            distribution=tfd.MultivariateNormalTriL(loc=mu, scale_tril=sigma_cholesky),
            bijector=tfb.Invert(NormalCDF()),
            validate_args=False,
            name="GaussianCopula")

class NormalCDF(tfb.Bijector):
    """Bijector that encodes normal CDF and inverse CDF functions.
    
    We follow the convention that the `inverse` represents the CDF
    and `forward` the inverse CDF (the reason for this convention is
    that inverse CDF methods for sampling are expressed a little more
    tersely this way).
    
    Attributes:
        normal_distribution (tfd.Normal): Description
    
    """
    def __init__(self):
        """Summary
        """
        self.normal_distribution = tfd.Normal(loc=0.0, scale=1.0)
        
        super().__init__(
            forward_min_event_ndims=0,
            validate_args=False,
            name="NormalCDF")

    def _forward(self, y):  #pylint: disable=W0221
        """Inverse CDF of normal distribution.
        
        Args:
            y (Union[float, tf.Tensor]): Description
        
        Returns:
            tf.Tensor: F^{-1}(y)
        """
        return self.normal_distribution.quantile(y)

    def _inverse(self, x):  #pylint: disable=W0221
        """CDF of normal distribution. 
        
        Args:
            x (Union[float, tf.Tensor]): Description
        
        Returns:
            tf.Tensor: F(x)
        """
        
        return self.normal_distribution.cdf(x)

    def _inverse_log_det_jacobian(self, x): #pylint: disable=C0103
        """Log PDF of the normal distribution
        
        Args:
            x (Union[float, tf.Tensor]): Description
        
        Returns:
            tf.Tensor: log(f(x))
        """

        return self.normal_distribution.log_prob(x)
