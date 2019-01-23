# syntax=docker/dockerfile:1.0.0-experimental
FROM nvidia/cuda:9.0-cudnn7-runtime-ubuntu16.04

RUN apt-get update && \
	apt-get install software-properties-common -y && \
	add-apt-repository ppa:deadsnakes/ppa -y && \
	apt-get update && \
	apt-get install python3.6 git wget -y && \
	ln -s /usr/bin/python3.6 /usr/local/bin/python3 && \
	wget https://bootstrap.pypa.io/get-pip.py && \
	python3 get-pip.py && \
	rm get-pip.py && \
	mkdir app /var/log/sacred_tensorboard  /var/moonraker_models

ADD ./ /app

ENV JUPYTER_PATH=/app/lib

ENV PYTHONPATH=/app/lib

RUN --mount=type=secret,id=gitlab_credentials.ini \ 
	cd /app && \
	pip3 install --process-dependency-links -e .[jupyter,gpu]

WORKDIR /app
