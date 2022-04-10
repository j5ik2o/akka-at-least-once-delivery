package example.processManager.billing

import java.util.UUID

object BillingProtocol {
  sealed trait CommandRequest {
    def id: UUID
    def billingId: BillingId
  }

  final case class CreateBilling(id: UUID, billingId: BillingId, )
}
