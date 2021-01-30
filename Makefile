phony: build build-image-cpu install install-dev install-gpu test test-mypy test-pylint

PYTEST_IGNORE = --ignore=fakebase_pb2.py \
	--ignore=fakebase_pb2_grpc.py \
	--ignore=events_pb2.py \
	--ignore=events_pb2_grpc.py \
	--ignore=environment_pb2_grpc.py \
	--ignore=environment_pb2.py \
	--ignore=environment_pb2_grpc.py \
	--ignore=scalapb \
	--ignore=scala

build:
	make build-scala build-python-protos

build-scala:
	cd scala && make build $(project)

build-python-protos:
	chmod u+x bin/build_python_protos && bin/build_python_protos

build-image-cpu:
	docker build -f dockerfiles/Dockerfile.cpu -t registry.gitlab.com/moonraker/coinbase_train/cpu:${TAG} .

build-image-gpu:
	docker build -f dockerfiles/Dockerfile.gpu -t registry.gitlab.com/moonraker/coinbase_train/gpu:${TAG} .

clean:
	chmod u+x bin/clean && bin/clean

clean-local-storage:
	chmod u+x bin/clean_local_storage && bin/clean_local_storage

install:
	cd python && pip3 install --upgrade pip && pip3 install -e .[grpc]

install-dev:
	cp bin/pre-commit .git/hooks/ && chmod u+x .git/hooks/pre-commit && cd python && pip3 install -e .[dev,grpc]

install-gpu:
	cd python && pip3 install -e .[gpu,grpc]

test-python:
	cd python && python3 setup.py test --addopts "${PYTEST_IGNORE} --mypy --pylint --pylint-rcfile=setup.cfg"

test-mypy:
	cd python && python3 setup.py test --addopts "${PYTEST_IGNORE} -m mypy --mypy"

test-pylint:
	cd python && python3 setup.py test --addopts "${PYTEST_IGNORE} -m pylint --pylint --pylint-rcfile=setup.cfg"

test-unit:
	cd python && python3 setup.py test --addopts "-s ${PYTEST_IGNORE}"

test-scala:
	cd scala && make test

logs-train-driver:
	chmod u+x bin/view_logs && bin/view_logs coinbaseml-train job-train

logs-trend-following-driver:
	chmod u+x bin/view_logs && bin/view_logs coinbaseml-trend-following trend-following

ui-notbook:
	chmod u+x bin/port_forward && bin/port_forward notbook-train 8888

ui-ray-train:
	chmod u+x bin/port_forward && bin/port_forward ray-head 8265 coinbaseml-train

ui-ray-trend-following:
	chmod u+x bin/port_forward && bin/port_forward ray-head 8265 coinbaseml-trend-following

ui-sacred:
	kubectl port-forward -n moonraker svc/sacred-sacredboard 5000:5000
