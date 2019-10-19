phony: install install-dev install-gpu test test-mypy test-pylint

install:
	python3 setup.py install

install-dev:
	python3 setup.py install --dev && cp bin/pre-commit .git/hooks/ && chmod u+x .git/hooks/pre-commit

install-gpu:
	python3 setup.py install --gpu

test:
	python3 setup.py test --addopts "-v --mypy --pylint --pylint-rcfile=setup.cfg"

test-mypy:
	python3 setup.py test --addopts "-v -m mypy --mypy"

test-pylint:
	python3 setup.py test --addopts "-v -m pylint --pylint --pylint-rcfile=setup.cfg"
