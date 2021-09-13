"""Generates datasets for training and evaluating a supervisd learning model
"""
from datetime import timedelta, datetime
from typing import TYPE_CHECKING, Tuple, Dict, Any

import pandas as pd
from dateutil.parser import parse

from actionizers_pb2 import Actionizer
from coinbase_ml.common import constants as cc
from coinbase_ml.common.featurizers import build_featurizer_configs
from coinbase_ml.common.types import FeatureName
from coinbase_ml.fakebase.exchange import Exchange
from coinbase_ml.fakebase.types import ProductVolume, QuoteVolume, ProductId
from coinbase_ml.supervised.constants import FeatureNames
from coinbase_ml.supervised.experiment_configs import SACRED_EXPERIMENT
from coinbase_ml.supervised.optimize_labels import generate_optimal_labels
from coinbase_ml.supervised.types import Features, Prices
from environment_pb2 import RewardStrategy, InfoDictKey
from featurizers_pb2 import (
    FeaturizerConfigs,
    Featurizer,
)

if TYPE_CHECKING:
    import coinbase_ml.common.protos.featurizers_pb2 as featurizers_pb2


def generate_features_and_prices(
    start_dt: datetime,
    end_dt: datetime,
    time_delta: timedelta,
    initial_product_funds: ProductVolume,
    initial_quote_funds: QuoteVolume,
    num_warmup_time_steps: int,
    featurizer: "featurizers_pb2.FeaturizerValue",
    featurizer_configs: FeaturizerConfigs,
    product_id: ProductId,
) -> Tuple[Features, Prices]:
    """Generates features DataFrame and price time series
    """
    exchange = Exchange()

    simulation_id = exchange.start(
        start_dt=start_dt,
        end_dt=end_dt,
        time_delta=time_delta,
        initial_product_funds=initial_product_funds,
        initial_quote_funds=initial_quote_funds,
        num_warmup_time_steps=num_warmup_time_steps,
        actionizer=Actionizer.NoOpActionizer,
        featurizer=featurizer,
        featurizer_configs=featurizer_configs,
        reward_strategy=RewardStrategy.ReturnRewardStrategy,
        backup_to_cloud_storage=True,
        enable_progress_bar=True,
        product_id=product_id,
    ).simulation_id

    features_dict = {}
    prices_list = []
    while not exchange.finished(simulation_id):
        exchange.step(simulation_id)

        interval_start_dt = exchange.interval_start_dt(simulation_id)
        order_book_features = exchange.features(simulation_id)[
            FeatureName(FeatureNames.ORDER_BOOK)
        ]
        mid_price = exchange.simulation_metadata(
            simulation_id
        ).simulation_info.observation.info.infoDict.infoDict[
            InfoDictKey.Name(InfoDictKey.midPrice)
        ]

        time_stamp = pd.Timestamp(interval_start_dt)

        prices_list.append(mid_price)
        features_dict[time_stamp] = order_book_features

    features = Features(pd.DataFrame.from_dict(features_dict, orient="index"))  # type: ignore
    prices = Prices(pd.Series(prices_list, index=features.index))

    return features, prices


@SACRED_EXPERIMENT.automain
def generate_features_and_labels(
    start_dt: str,
    end_dt: str,
    time_delta: int,
    initial_product_funds: str,
    initial_quote_funds: str,
    num_warmup_time_steps: int,
    featurizer: str,
    featurizer_configs: Dict[str, Any],
) -> float:
    """Generates features and labels based on parameters specified in Sacred config
    """
    _featurizer = Featurizer.Value(featurizer)
    features, prices = generate_features_and_prices(
        parse(start_dt),
        parse(end_dt),
        timedelta(seconds=time_delta),
        cc.PRODUCT_ID.product_volume_type(str(initial_product_funds)),
        cc.PRODUCT_ID.quote_volume_type(str(initial_quote_funds)),
        num_warmup_time_steps,
        _featurizer,
        build_featurizer_configs(_featurizer, featurizer_configs),
        cc.PRODUCT_ID,
    )
    (
        labels,
        performance_metric,
    ) = generate_optimal_labels(  # pylint: disable=no-value-for-parameter
        prices=prices
    )
    print(features.join(labels))

    return performance_metric
