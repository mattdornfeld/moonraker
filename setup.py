"""Summary
"""
import logging
import subprocess

from setuptools import find_packages, setup

logging.basicConfig()
LOGGER = logging.getLogger(__name__)

GRPC_REQUIRES = [
    "2to3",
    "grpcio>=1.28.0,<2.0.0",
    "grpcio-tools>=1.28.0,<1.30.0",
    "mypy-protobuf",
]

INSTALL_REQUIRES = [
    "GitPython>=2.1.10,<3.0.0",
    "dnspython>=1.16.0, <2.0.0",
    "funcy>=1.11.0,<2.0.0",
    "google-cloud-storage>=1.15.0,<2.0.0",
    "incense>=0.0.10",
    "python-dateutil>=2.6.0,<3.0.0",
    "pytimeparse>=1.1.0,<2.0.0",
    "requests>=2.20.0,<3.0.0",
    "sacred",
    "ray[rllib]==0.7.6",
    "tensorflow==2.0.0",
    "kafka-python>=1.4.7,<2.0.0",
]

# bintrees needs cython installed first in order to use its cython compiled tree
PRIORITY_INSTALL = ["cython"]

SCRIPTS = [
    "bin/connect_to_ray_cluster",
    "bin/notebook_entrypoint",
    "bin/start_jupyter_lab",
    "bin/serve_job_entrypoint",
    "bin/train_job_entrypoint",
]

SETUP_REQUIRES = ["cython", "pytest-runner>=5.1,<6.0"]

TESTS_REQUIRE = [
    "docker>=3.3.0,<4.0.0",
    "pytest>=4.0.2,<5.0.0",
    "pytest-cases>=1.11.1,<1.13.0",
    "pytest-mypy>=0.4.0,<0.5.0",
    "pytest-pylint>=0.14.1,<0.15",
    "wrapt==1.11.2",
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
    extras_require=dict(
        dev=["black>=19.3b0,<20.0"] + TESTS_REQUIRE,
        gpu=["tensorflow-gpu==2.0.0"],
        grpc=GRPC_REQUIRES,
    ),
    install_requires=INSTALL_REQUIRES,
    name="coinbase_ml",
    packages=find_packages(),
    scripts=SCRIPTS,
    setup_requires=SETUP_REQUIRES,
    tests_require=TESTS_REQUIRE,
    version="0.1",
)
