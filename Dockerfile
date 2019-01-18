FROM ubuntu:16.04

RUN apt-get update 

RUN apt-get install git python3-pip -y

RUN mkdir app 

RUN mkdir /var/log/sacred_tensorboard

RUN mkdir /var/moonraker_models

ENV PYTHONPATH=/app:$PYTHONPATH

RUN pip3 install --upgrade pip

ADD ./ /app/coinbase_train

RUN cp -r /app/coinbase_train/lib/rl /app/

RUN pip3 install -r /app/coinbase_train/requirements.txt

WORKDIR /app/coinbase_train