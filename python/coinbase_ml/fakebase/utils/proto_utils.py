"""Utils for working with fakebase protos
"""
from coinbase_ml.fakebase.orm import CoinbaseOrder
from coinbase_ml.fakebase.protos.events_pb2 import Order
from coinbase_ml.fakebase.utils.exceptions import OrderNotFoundException


def order_from_sealed_value(sealed_order: Order) -> CoinbaseOrder:
    """
    order_from_sealed_value [summary]

    Args:
        sealed_order (Order): [description]

    Raises:
        OrderNotFoundException: [description]

    Returns:
        Option[CoinbaseOrder]: [description]
    """
    sealed_value = sealed_order.WhichOneof("sealed_value")
    if sealed_value == "buyLimitOrder":
        order = CoinbaseOrder.from_proto(sealed_order.buyLimitOrder)
    elif sealed_value == "buyMarketOrder":
        order = CoinbaseOrder.from_proto(sealed_order.buyMarketOrder)
    elif sealed_value == "sellLimitOrder":
        order = CoinbaseOrder.from_proto(sealed_order.sellLimitOrder)
    elif sealed_value == "sellMarketOrder":
        order = CoinbaseOrder.from_proto(sealed_order.sellMarketOrder)
    else:
        raise OrderNotFoundException

    return order
