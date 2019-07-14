"""Summary
"""
from configparser import ConfigParser
import os
import sys

from setuptools import setup, find_packages

GPU = False
if "--gpu" in sys.argv:
    GPU = True
    sys.argv.remove("--gpu")

CONFIG = ConfigParser()
#The credentials secret will be mounted in the below directory
#when building a docker image.
CONFIG.read('/run/secrets/gitlab_credentials.ini')

try:
    GITLAB_PASSWORD = CONFIG['CREDENTIALS']['GITLAB_PASSWORD']
    GITLAB_USERNAME = CONFIG['CREDENTIALS']['GITLAB_USERNAME']
except KeyError:
    GITLAB_PASSWORD = os.environ['GITLAB_PASSWORD']
    GITLAB_USERNAME = os.environ['GITLAB_USERNAME']

GITLAB_PREFIX = f'git+https://{GITLAB_USERNAME}:{GITLAB_PASSWORD}@gitlab.com/moonraker'

DEPENDENCY_LINKS = [
    f'{GITLAB_PREFIX}/fakebase@v0.7.0#egg=fakebase-0.1',
    f'{GITLAB_PREFIX}/keras-rl@master#egg=keras-rl-0.4.2'
    ]
    
INSTALL_REQUIRES = [
    'GitPython>=2.1.10,<2.2.0',
    'keras>=2.2.4,<2.3.0',
    'fakebase',
    'funcy>=1.11.0,<1.12.0',
    'jupyterlab',
    'pymongo>=3.5.0',
    'python-dateutil>=2.6.0,<2.7.0',
    'keras-rl',
    'sacred>=0.7.2,<0.8.0',
    f"tensorflow{'-gpu' if GPU else ''}>=1.13.1,<1.14.0",
    'tensorflow-probability>=0.6.0,<0.7.0']

SETUP_REQUIRES = ['cython']

setup(
    author='Matthew Dornfeld',
    author_email='matt@firstorderlabs.co',
    dependency_links=DEPENDENCY_LINKS,
    extras_require=dict(gpu=['tensorflow-gpu>=1.13.1,<1.14.0']),
    install_requires=INSTALL_REQUIRES,
    name="coinbase_train",
    packages=find_packages(),
    setup_requires=SETUP_REQUIRES,
    version="0.1"
)
