FROM ubuntu:16.04

RUN apt-get update 

RUN apt-get install git python3-pip -y

RUN mkdir python3_lib 

RUN mkdir /var/log/sacred_tensorboard

RUN mkdir /var/moonraker_models

ENV PYTHONPATH=/python3_lib:$PYTHONPATH

RUN pip3 install --upgrade pip

ADD ./ /python3_lib/gdax_train

RUN cp -r /python3_lib/gdax_train/lib/rl /python3_lib/

RUN pip3 install -r /python3_lib/gdax_train/requirements.txt

WORKDIR /python3_lib/gdax_train