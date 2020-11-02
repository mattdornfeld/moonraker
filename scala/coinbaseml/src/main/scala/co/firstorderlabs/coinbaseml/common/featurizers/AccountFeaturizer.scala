package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.common.protos.ObservationRequest
import co.firstorderlabs.coinbaseml.common.utils.Utils.When
import co.firstorderlabs.coinbaseml.fakebase.Wallets
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}

/** Generates Account specific features
  *
  */
object AccountFeaturizer {

  def construct(observationRequest: ObservationRequest): List[Double] =
    normalizedFunds(observationRequest.normalize)

  /** A List of normalized funds of the form List(quoteBalance, quoteHolds, productBalance, productHolds)
    *
    * @return
    */
  private def normalizedFunds(normalize: Boolean): List[Double] = {
    val productWallet = Wallets.getWallet(ProductVolume)
    val quoteWallet = Wallets.getWallet(QuoteVolume)

    List(
      quoteWallet.balance.whenElse(normalize)(_.normalize, _.toDouble),
      quoteWallet.holds.whenElse(normalize)(_.normalize, _.toDouble),
      productWallet.balance.whenElse(normalize)(_.normalize, _.toDouble),
      productWallet.holds.whenElse(normalize)(_.normalize, _.toDouble)
    )
  }

}
