package example.processManager.billing

import example.CborSerializable

sealed trait BillingError extends CborSerializable {
  def billingId: BillingId
}

object BillingError {
  case class CreditError(billingId: BillingId) extends BillingError
}
