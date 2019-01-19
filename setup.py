"""Summary
"""
from configparser import ConfigParser
import os
from setuptools import setup, find_packages

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

#TODO: Version fakebase module
DEPENDENCY_LINKS = [
    f'git+https://{GITLAB_USERNAME}:{GITLAB_PASSWORD}@gitlab.com'
    '/Moonraker/fakebase@master#egg=fakebase-0.1']

REQUIREMENTS = [
    'GitPython==2.1.10',
    'keras==2.1.6',
    'fakebase',
    'phased_lstm_keras==1.0.2',
    'pymongo==3.6.1',
    'python-dateutil==2.7.3',
    'sacred==0.7.2',
    'tensorflow==1.8.0']

setup(
    dependency_links=DEPENDENCY_LINKS,
    extras_require=dict(jupyter=["jupyterlab"], gpu=['tensorflow-gpu==1.8.0']),
    install_requires=REQUIREMENTS,
    name="coinbase_train",
    packages=find_packages(),
    version="0.1"
)
