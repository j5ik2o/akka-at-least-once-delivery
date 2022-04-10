package example.processManager.billing

sealed trait BillingError {
  def billingId: BillingId
}

object BillingError {
  case class CreditError(billingId: BillingId) extends BillingError
}
