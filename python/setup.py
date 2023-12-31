"""Summary
"""
import logging

from setuptools import find_packages, setup

logging.basicConfig()
LOGGER = logging.getLogger(__name__)

GRPC_REQUIRES = [
    "grpcio>=1.28.0,<2.0.0",
    "grpcio-tools>=1.28.0,<1.30.0",
    "mypy-protobuf",
]

DEPENDENCY_LINKS = {
    "cbpro": "git+https://github.com/danpaquin/coinbasepro-python@"
    "0a9dbd86a25ae266d0e0eefeb112368c284b7dcc#egg=cbpro-1.1.4"
}


FAKEBASE_REQUIRES = [
    "sqlalchemy>=1.3.0,<2.0.0",
    "sqlalchemy_utils>=0.36.8,<0.37.0",
    "psycopg2-binary>=2.8.5,<3.0.0",
    f"cbpro @ {DEPENDENCY_LINKS['cbpro']}",
]

GPU_REQUIRES = ["gputil>=1.4.0,<2.0.0", "tensorflow-gpu==2.0.0"]

INSTALL_REQUIRES = [
    "GitPython>=2.1.10,<3.0.0",
    "dnspython>=1.16.0, <2.0.0",
    "flask>=1.1.2, <2.0.0",
    "funcy>=1.11.0,<2.0.0",
    "google-cloud-bigquery[bqstorage,pandas]~=2.0",
    "google-cloud-storage~=1.0",
    "hyperopt>=0.2.5,<0.3.0",
    "incense>=0.0.10",
    "nptyping>=1.3.0<2.0.0",
    "python-dateutil>=2.6.0,<3.0.0",
    "pytimeparse>=1.1.0,<2.0.0",
    "requests>=2.20.0,<3.0.0",
    "sacred",
    "ray[rllib,tune]==1.2.0",
    "tensorflow==2.0.0",
    "vectorbt~=0.21.0",
    "kafka-python>=1.4.7,<2.0.0",
    "types-python-dateutil",
    "types-requests",
    "types-protobuf",
    "pandas-stubs",
]

SCRIPTS = [
    "../bin/connect_to_ray_cluster",
    "../bin/clean",
    "../bin/clean_local_storage",
    "../bin/notebook_entrypoint",
    "../bin/start_jupyter_lab",
    "../bin/storagewriter_entrypoint",
    "../bin/serve_job_entrypoint",
    "../bin/train_job_entrypoint",
    "../bin/trend_following_optimization_entrypoint",
]

SETUP_REQUIRES = ["pytest-runner>=5.1,<6.0"]

TESTS_REQUIRE = [
    "docker>=3.3.0,<4.0.0",
    "pytest>=4.0.2,<5.0.0",
    "pytest-cases>=1.11.1,<1.13.0",
    "pytest-mypy>=0.4.0,<0.5.0",
    "pytest-pylint>=0.14.1,<0.15",
    "pylint < 3.0.0",
    "wrapt==1.11.2",
    "pytest-lazy-fixture",
    "sqlvalidator",
]

setup(
    author="Matthew Dornfeld",
    author_email="matt@firstorderlabs.co",
    extras_require=dict(
        dev=["black>=19.3b0,<20.0"] + TESTS_REQUIRE,
        gpu=GPU_REQUIRES,
        grpc=GRPC_REQUIRES,
    ),
    install_requires=INSTALL_REQUIRES + FAKEBASE_REQUIRES,
    name="coinbase_ml",
    packages=find_packages(),
    scripts=SCRIPTS,
    setup_requires=SETUP_REQUIRES,
    tests_require=TESTS_REQUIRE,
    version="0.1",
)
