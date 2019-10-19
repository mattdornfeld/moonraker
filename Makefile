phony: install install-dev test test-integration test-mypy test-pylint test-unit

install:
	python3 setup.py install

install-dev:
	pip3 install .[dev] && cp bin/pre-commit .git/hooks/ && chmod u+x .git/hooks/pre-commit

test:
	python3 setup.py test --addopts "-v --mypy --pylint --pylint-rcfile=setup.cfg"

test-integration:
	python3 setup.py test --addopts "-v -m 'integration_tests'"

test-mypy:
	python3 setup.py test --addopts "-v -m mypy --mypy"

test-pylint:
	python3 setup.py test --addopts "-v -m pylint --pylint --pylint-rcfile=setup.cfg"

test-unit:
	python3 setup.py test --addopts "-v -m 'not integration_tests'"
