# coinbase_ml
This is a monorepo containing code for training, evaluating, and serving a machine learning model for trading on the Coinbase Pro exchange.
# Setup
Run
```
make install-dev
```
to install the project dependencies.
# Building
To build the Scala portion of the project run
```
make build-scala
```
This will run the Scala test suite and build a fat jar containing the Scala portion of the project. The jar will be located in the top level of this repo. On the Python side only the proto files need to be built. To do so run
```
make build-python-protos
```
# Testing
There are a few different tests you can run to verify the integrity of your changes (see the Makefile for a complete list). To run the Scala test suite run
```
make test-scala
```
To run the Python test suite run
```
make test-python
```
You can also run subsets of the Python test suite with the following commands
```
make test-unit test-mypy test-pylint
```
# Running Locally
Last if you set the appropriate values for the environment variables `DB_HOST`, `POSTGRES_USERNAME`, and `POSTGRES_PASSWORD` you can run
```
python3 coinbase_ml/train/train.py -u
```
to execute an end to end test, using the test configs. It should take a minute or two to run.
# Ideas This Repo is Based On
[Continuous control with deep reinforcement learning](https://arxiv.org/abs/1509.02971)

[DeepLOB: Deep Convolutional Neural Networks for Limit Order Books](https://arxiv.org/abs/1808.03668)

[An Empirical Evaluation of Generic Convolutional and Recurrent Networks for Sequence Modeling](https://arxiv.org/abs/1803.01271)

[Human-level control through Deep Reinforcement Learning](https://storage.googleapis.com/deepmind-media/dqn/DQNNaturePaper.pdf)

[Hierarchical Attention Networks for Document Classification](https://www.cs.cmu.edu/~hovy/papers/16HLT-hierarchical-attention-networks.pdf)

[WaveNet: A Generative Model for Raw Audio](https://arxiv.org/abs/1609.03499)