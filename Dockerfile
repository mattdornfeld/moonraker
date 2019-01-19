# syntax=docker/dockerfile:1.0.0-experimental
FROM ubuntu:18.04

RUN apt-get update && \
	apt-get install git python3-pip -y && \
	pip3 install --upgrade pip && \
	mkdir app /var/log/sacred_tensorboard  /var/moonraker_models

ADD ./ /app

RUN --mount=type=secret,id=gitlab_credentials.ini \ 
	cd /app && \
	pip3 install --process-dependency-links -e .[jupyter,gpu] && \
	apt-get remove python3-pip -y

WORKDIR /app
