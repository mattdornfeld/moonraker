"""
 [summary]
"""
import json

from kafka import KafkaConsumer

from coinbase_ml.serve.constants import COINBASE_STREAM_KAFKA_TOPIC


if __name__ == "__main__":
    CONSUMER = KafkaConsumer(
        COINBASE_STREAM_KAFKA_TOPIC,
        auto_offset_reset="earliest",
        enable_auto_commit=True,
        group_id=None,
        value_deserializer=lambda x: json.loads(x.decode("utf-8")),
    )

    for msg in CONSUMER:
        print(msg.value)
