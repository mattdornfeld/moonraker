from setuptools import setup, find_packages

setup(
    name="coinbase_train",
    version="0.1",
    packages=find_packages(),
    install_requires=[r.replace('\n', '') for r in list(open('./requirements.txt'))]
)