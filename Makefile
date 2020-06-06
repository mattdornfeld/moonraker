phony: build build-image-cpu install install-dev install-gpu test test-mypy test-pylint

PYTEST_IGNORE = --ignore=coinbase_ml/fakebase/protos \
	--ignore=coinbase_ml/fakebase/exchange_new.py \
	--ignore=coinbase_ml/fakebase/account_new.py \
	--ignore=scalapb

build-scala:
	cd scala && sbt compile

build-python-protos:
	chmod u+x bin/build_python_protos && bin/build_python_protos

build-image-cpu:
	docker build -f dockerfiles/Dockerfile.cpu -t registry.gitlab.com/moonraker/coinbase_train/cpu:${TAG} .

build-image-gpu:
	docker build -f dockerfiles/Dockerfile.gpu -t registry.gitlab.com/moonraker/coinbase_train/gpu:${TAG} .

clean:
	chmod u+x bin/clean && bin/clean

install:
	pip3 install -e .[grpc]

install-dev:
	pip3 install -e .[dev,grpc] && cp bin/pre-commit .git/hooks/ && chmod u+x .git/hooks/pre-commit

install-gpu:
	pip3 install -e .[gpu,grpc]

test:
	python3 setup.py test --addopts "${PYTEST_IGNORE} --mypy --pylint --pylint-rcfile=setup.cfg"

test-integration:
	python3 setup.py test --addopts "${PYTEST_IGNORE} -m 'integration_tests'"

test-mypy:
	python3 setup.py test --addopts "${PYTEST_IGNORE} -m mypy --mypy"

test-pylint:
	python3 setup.py test --addopts "${PYTEST_IGNORE} -m pylint --pylint --pylint-rcfile=setup.cfg"

test-unit:
	python3 setup.py test --addopts "${PYTEST_IGNORE} -m 'not integration_tests'"
