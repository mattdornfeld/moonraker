package co.firstorderlabs.common.featurizers

import co.firstorderlabs.common.protos.featurizer.ObservationRequest
import co.firstorderlabs.fakebase.Wallets
import co.firstorderlabs.fakebase.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}

/** Generates Account specific features
  *
  */
object AccountFeaturizer {

  def construct(observationRequest: ObservationRequest): List[Double] =
    normalizedFunds

  /** A List of normalized funds of the form List(quoteBalance, quoteHolds, productBalance, productHolds)
    *
    * @return
    */
  private def normalizedFunds: List[Double] = {
    val productWallet = Wallets.getWallet(ProductVolume)
    val quoteWallet = Wallets.getWallet(QuoteVolume)

    List(
      quoteWallet.balance.normalize,
      quoteWallet.holds.normalize,
      productWallet.balance.normalize,
      productWallet.holds.normalize
    )
  }

}
