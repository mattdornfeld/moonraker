phony: build-image-cpu install install-dev install-gpu test test-mypy test-pylint

build-image-cpu:
docker build -f dockerfiles/Dockerfile.cpu -t registry.gitlab.com/moonraker/coinbase_train/cpu:${TAG} .

build-image-gpu:
	docker build -f dockerfiles/Dockerfile.gpu -t registry.gitlab.com/moonraker/coinbase_train/gpu:${TAG} .

install:
	pip3 install -e .

install-dev:
	pip3 install -e .[dev] && cp bin/pre-commit .git/hooks/ && chmod u+x .git/hooks/pre-commit

install-gpu:
	pip3 install -e .[gpu]

test:
	python3 setup.py test --addopts "--mypy --pylint --pylint-rcfile=setup.cfg"

test-integration:
	python3 setup.py test --addopts "-m 'integration_tests'"

test-mypy:
	python3 setup.py test --addopts "-m mypy --mypy"

test-pylint:
	python3 setup.py test --addopts "-m pylint --pylint --pylint-rcfile=setup.cfg"

test-unit:
	python3 setup.py test --addopts "-m 'not integration_tests'"
