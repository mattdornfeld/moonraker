package co.firstorderlabs.coinbaseml.fakebase.types

object Events {
  trait OrderRequest

  trait BuyOrderRequest extends OrderRequest

  trait SellOrderRequest extends OrderRequest

  trait LimitOrderRequest extends OrderRequest {
    val postOnly: Boolean
  }

  trait MarketOrderRequest extends OrderRequest
}
