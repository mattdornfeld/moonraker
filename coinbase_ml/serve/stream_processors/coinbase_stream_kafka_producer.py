"""
 [summary]
"""
import json
import logging
import os
from typing import Any, Dict, List, Optional, Union

from cbpro import WebsocketClient
from kafka import KafkaProducer
from kafka.admin import KafkaAdminClient, NewTopic
from kafka.errors import TopicAlreadyExistsError

from coinbase_ml.common import constants as cc
from coinbase_ml.serve.constants import (
    COINBASE_WEBSOCKET_API_URL,
    COINBASE_WEBSOCKET_CHANNELS,
)

logging.basicConfig()
LOGGER = logging.getLogger(__name__)
LOGGER.setLevel(logging.DEBUG)


class CoinbaseKafkaStreamProducer(WebsocketClient):
    """
    [summary]
    """

    KAFKA_CLIENT_ID = "moonraker_coinbase_stream_producer"

    def __init__(
        self,
        kafka_topic: str,
        kafka_bootstrap_servers: Optional[Union[str, List[str]]] = None,
    ) -> None:
        super(CoinbaseKafkaStreamProducer, self).__init__(
            channels=COINBASE_WEBSOCKET_CHANNELS,
            products=[str(cc.PRODUCT_ID)],
            url=COINBASE_WEBSOCKET_API_URL,
        )

        self.topic = kafka_topic
        self.kafka_bootstrap_servers = kafka_bootstrap_servers
        if not kafka_bootstrap_servers:
            self.kafka_bootstrap_servers = ["localhost:9092"]

        self.kafka_producer = KafkaProducer(
            bootstrap_servers=self.kafka_bootstrap_servers,
            client_id=self.KAFKA_CLIENT_ID,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
        )

        self.create_topic()

    def create_topic(self) -> None:
        """
        This function creates the topic for the producer and configures a 3 hour
        retention policy on the topic.
        """
        admin_client = KafkaAdminClient(
            bootstrap_servers=self.kafka_bootstrap_servers,
            client_id=self.KAFKA_CLIENT_ID,
        )
        topic_list = [
            NewTopic(
                name=self.topic,
                num_partitions=1,
                replication_factor=1,
                topic_configs={"retention.ms": 3 * 60 * 60 * 1000},
            )
        ]
        try:
            LOGGER.info("Creating topics: %s", topic_list)
            admin_client.create_topics(new_topics=topic_list, validate_only=False)
            LOGGER.info("Topic created")
        except TopicAlreadyExistsError:
            LOGGER.info("Topic already exists")

    def on_message(self, msg: Dict[str, Any]) -> None:
        """
        This function is called by the Coinbase SDK once per message
        """
        LOGGER.debug(msg)
        self.kafka_producer.send(self.topic, msg)


if __name__ == "__main__":
    BOOTSTRAP_SERVERS = os.environ.get(
        "KAFKA_BOOTSTRAP_SERVERS", "localhost:31090"
    ).split(",")
    TOPIC = os.environ.get("KAFKA_TOPIC", "test-topic")
    STREAM = CoinbaseKafkaStreamProducer(
        kafka_topic=TOPIC, kafka_bootstrap_servers=BOOTSTRAP_SERVERS
    )
    STREAM.start()
