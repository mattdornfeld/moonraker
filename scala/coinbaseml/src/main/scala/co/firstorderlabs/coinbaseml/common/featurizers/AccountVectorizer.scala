package co.firstorderlabs.coinbaseml.common.featurizers

import co.firstorderlabs.coinbaseml.common.utils.Utils.When
import co.firstorderlabs.coinbaseml.fakebase.{SimulationState, Wallets, WalletsState}
import co.firstorderlabs.common.currency.Configs.ProductPrice.{ProductVolume, QuoteVolume}
import co.firstorderlabs.common.protos.environment.ObservationRequest

/** Generates Account specific features
  *
  */
object AccountVectorizer extends VectorizerBase {
  /** A List of normalized funds of the form List(quoteBalance, quoteHolds, productBalance, productHolds)
    *
    * @return
    */
  private def normalizedFunds(normalize: Boolean)(implicit walletState: WalletsState): List[Double] = {
    val productWallet = Wallets.getWallet(ProductVolume)
    val quoteWallet = Wallets.getWallet(QuoteVolume)

    List(
      quoteWallet.balance.whenElse(normalize)(_.normalize, _.toDouble),
      quoteWallet.holds.whenElse(normalize)(_.normalize, _.toDouble),
      productWallet.balance.whenElse(normalize)(_.normalize, _.toDouble),
      productWallet.holds.whenElse(normalize)(_.normalize, _.toDouble)
    )
  }

  override def construct(observationRequest: ObservationRequest)(implicit simulationState: SimulationState): List[Double] =
    normalizedFunds(observationRequest.normalize)(simulationState.accountState.walletsState)

  override def step(implicit simulationState: SimulationState): Unit = ???
}
