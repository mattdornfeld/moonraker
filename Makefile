phony: install install-dev install-gpu test test-mypy test-pylint

install:
	pip3 install -e .

install-dev:
	pip3 install -e .[dev] && cp bin/pre-commit .git/hooks/ && chmod u+x .git/hooks/pre-commit

install-gpu:
	pip3 install -e .[gpu]

test:
	python3 setup.py test --addopts "-v --mypy --pylint --pylint-rcfile=setup.cfg"

test-mypy:
	python3 setup.py test --addopts "-v -m mypy --mypy"

test-pylint:
	python3 setup.py test --addopts "-v -m pylint --pylint --pylint-rcfile=setup.cfg"
