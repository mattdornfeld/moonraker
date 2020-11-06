phony: build build-image-cpu install install-dev install-gpu test test-mypy test-pylint

PYTEST_IGNORE = --ignore=fakebase_pb2.py \
	--ignore=fakebase_pb2_grpc.py \
	--ignore=events_pb2.py \
	--ignore=events_pb2_grpc.py \
	--ignore=environment_pb2_grpc.py \
	--ignore=environment_pb2.py \
	--ignore=environment_pb2_grpc.py \
	--ignore=scalapb

build:
	make build-scala build-python-protos

build-scala:
	cd scala && \
	sbt "project $(project)" clean assembly

build-python-protos:
	chmod u+x bin/build_python_protos && bin/build_python_protos

build-image-cpu:
	docker build -f dockerfiles/Dockerfile.cpu -t registry.gitlab.com/moonraker/coinbase_train/cpu:${TAG} .

build-image-gpu:
	docker build -f dockerfiles/Dockerfile.gpu -t registry.gitlab.com/moonraker/coinbase_train/gpu:${TAG} .

clean:
	chmod u+x bin/clean && bin/clean

install:
	pip3 install --upgrade pip && pip3 install -e .[grpc]

install-dev:
	pip3 install -e .[dev,grpc] && cp bin/pre-commit .git/hooks/ && chmod u+x .git/hooks/pre-commit

install-gpu:
	pip3 install -e .[gpu,grpc]

test-python:
	python3 setup.py test --addopts "${PYTEST_IGNORE} --mypy --pylint --pylint-rcfile=setup.cfg"

test-mypy:
	python3 setup.py test --addopts "${PYTEST_IGNORE} -m mypy --mypy"

test-pylint:
	python3 setup.py test --addopts "${PYTEST_IGNORE} -m pylint --pylint --pylint-rcfile=setup.cfg"

test-unit:
	python3 setup.py test --addopts "-s ${PYTEST_IGNORE}"

test-scala:
	cd scala && sbt test
