"""Summary
"""
import logging
import os
import subprocess
from configparser import ConfigParser

from setuptools import find_packages, setup

logging.basicConfig()
LOGGER = logging.getLogger(__name__)

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
DEPENDENCY_LINKS = [
    f"{GITLAB_PREFIX}/fakebase@6e6306292d322a7b75f324460a3d7293b5bec83a#egg=fakebase-0.1"
]

RAY_WEBUI_REQUIRES = [
    "aiohttp",
    "bokeh>=1.4.0,<2.0.0",
    "ipywidgets>=7.5.0,<8.0.0",
    "psutil",
    "setproctitle",
]

INSTALL_REQUIRES = [
    "GitPython>=2.1.10,<3.0.0",
    "dnspython>=1.16.0, <2.0.0",
    "keras>=2.2.4,<3.0.0",
    f"fakebase @ {DEPENDENCY_LINKS[0]}",
    "funcy>=1.11.0,<2.0.0",
    "google-cloud-storage>=1.15.0,<2.0.0",
    "jupyterlab>=0.35.4,<0.36.0",
    "pymongo>=3.5.0,<4.0.0",
    "python-dateutil>=2.6.0,<3.0.0",
    "requests>=2.20.0,<3.0.0",
    "sacred",
    "ray[rllib]==0.7.6",
    f"tensorflow>=1.14.0,<1.15.0",
]

# bintrees needs cython installed first in order to use its cython compiled tree
PRIORITY_INSTALL = ["cython"]

SCRIPTS = [
    "bin/connect_to_ray_cluster",
    "bin/job_entrypoint",
    "bin/notebook_entrypoint",
    "bin/start_jupyter_lab",
]

SETUP_REQUIRES = ["cython", "pytest-runner>=5.1,<6.0"]

TESTS_REQUIRE = [
    "pytest>=4.0.2,<5.0.0",
    "pytest-mypy>=0.4.0,<0.5.0",
    "pytest-pylint>=0.14.1,<0.15",
]

try:
    subprocess.run(["pip3", "install"] + PRIORITY_INSTALL, check=True)
except subprocess.CalledProcessError:
    try:
        subprocess.run(["pip3", "install", "--user"] + PRIORITY_INSTALL, check=True)
    except subprocess.CalledProcessError as exception:
        LOGGER.warning(
            "Received exception %s. The bintrees cython dependencies will not be installed.",
            exception,
        )


setup(
    author="Matthew Dornfeld",
    author_email="matt@firstorderlabs.co",
    dependency_links=DEPENDENCY_LINKS,
    extras_require=dict(
        dev=["black>=19.3b0,<20.0"], gpu=["tensorflow-gpu>=1.14.0,<1.15.0"]
    ),
    install_requires=INSTALL_REQUIRES + RAY_WEBUI_REQUIRES,
    name="coinbase_train",
    packages=find_packages(),
    scripts=SCRIPTS,
    setup_requires=SETUP_REQUIRES,
    tests_require=TESTS_REQUIRE,
    version="0.1",
)
