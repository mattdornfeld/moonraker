"""Summary
"""
import logging
from numpy.random import seed

seed(353523591)

logging.basicConfig(
    level=logging.INFO,
    format='[%(asctime)s] {%(pathname)s:%(lineno)d} %(levelname)s - %(message)s')
    # datefmt='%H:%M:%S')
