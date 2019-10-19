"""Summary
"""
import os
import sys
from configparser import ConfigParser

from setuptools import find_packages, setup

GPU = False
if "--gpu" in sys.argv:
    GPU = True
    sys.argv.remove("--gpu")

DEV = False
if "--dev" in sys.argv:
    DEV = True
    sys.argv.remove("--dev")

CONFIG = ConfigParser()
# The credentials secret will be mounted in the below directory
# when building a docker image.
CONFIG.read("/run/secrets/gitlab_credentials.ini")

try:
    GITLAB_PASSWORD = CONFIG["CREDENTIALS"]["GITLAB_PASSWORD"]
    GITLAB_USERNAME = CONFIG["CREDENTIALS"]["GITLAB_USERNAME"]
except KeyError:
    GITLAB_PASSWORD = os.environ["GITLAB_PASSWORD"]
    GITLAB_USERNAME = os.environ["GITLAB_USERNAME"]

GITLAB_PREFIX = f"git+https://{GITLAB_USERNAME}:{GITLAB_PASSWORD}@gitlab.com/moonraker"

DEPENDENCY_LINKS = [f"{GITLAB_PREFIX}/fakebase@v0.8.0#egg=fakebase-0.1"]

DEV_INSTALL_REQUIRES = ["black>=19.3b0,<20.0"]

INSTALL_REQUIRES = [
    "GitPython>=2.1.10,<3.0.0",
    "dnspython>=1.16.0, <2.0.0",
    "keras>=2.2.4,<3.0.0",
    "fakebase",
    "funcy>=1.11.0,<2.0.0",
    "google-cloud-storage>=1.15.0,<2.0.0",
    "jupyterlab>=0.35.4,<0.36.0",
    "pymongo>=3.5.0,<4.0.0",
    "python-dateutil>=2.6.0,<3.0.0",
    "sacred",
    "ray[rllib]>=0.7.4,<0.8.0",
    f"tensorflow{'-gpu' if GPU else ''}>=1.14.0,<1.15.0",
]

INSTALL_REQUIRES += DEV_INSTALL_REQUIRES if DEV else []

SETUP_REQUIRES = ["cython", "pytest-runner>=5.1,<6.0"]

TESTS_REQUIRE = [
    "pytest>=4.0.2,<5.0.0",
    "pytest-mypy>=0.4.0,<0.5.0",
    "pytest-pylint>=0.14.1,<0.15",
]

setup(
    author="Matthew Dornfeld",
    author_email="matt@firstorderlabs.co",
    dependency_links=DEPENDENCY_LINKS,
    extras_require=dict(gpu=["tensorflow-gpu>=1.13.1,<1.14.0"]),
    install_requires=INSTALL_REQUIRES,
    name="coinbase_train",
    packages=find_packages(),
    setup_requires=SETUP_REQUIRES,
    tests_require=TESTS_REQUIRE,
    version="0.1",
)
