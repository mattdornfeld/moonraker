"""
 [summary]
"""
import json
import logging
from typing import Any, Dict, List, Optional, Union

from cbpro import WebsocketClient
from kafka import KafkaProducer

import fakebase.constants as fc
from coinbase_ml.serve.constants import (
    COINBASE_STREAM_KAFKA_TOPIC,
    COINBASE_WEBSOCKET_API_URL,
    COINBASE_WEBSOCKET_CHANNELS,
)

LOGGER = logging.getLogger(__name__)


class CoinbaseKafkaStreamProducer(WebsocketClient):
    """
    [summary]
    """

    def __init__(
        self,
        kafka_topic: str,
        kafka_bootstrap_servers: Optional[Union[str, List[str]]] = None,
    ) -> None:
        super(CoinbaseKafkaStreamProducer, self).__init__(
            channels=COINBASE_WEBSOCKET_CHANNELS,
            products=[fc.PRODUCT_ID],
            url=COINBASE_WEBSOCKET_API_URL,
        )

        self.topic = kafka_topic
        if not kafka_bootstrap_servers:
            kafka_bootstrap_servers = ["localhost:9092"]

        self.kafka_producer = KafkaProducer(
            bootstrap_servers=kafka_bootstrap_servers,
            client_id="moonraker_coinbase_stream_producer",
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
        )

    def on_message(self, msg: Dict[str, Any]) -> None:
        LOGGER.debug(msg)
        self.kafka_producer.send(self.topic, msg)


if __name__ == "__main__":
    STREAM = CoinbaseKafkaStreamProducer(kafka_topic=COINBASE_STREAM_KAFKA_TOPIC)
    STREAM.start()
