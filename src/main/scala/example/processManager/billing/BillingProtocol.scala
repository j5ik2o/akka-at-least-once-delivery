package example.processManager.billing

import akka.actor.typed.ActorRef

import java.util.UUID

object BillingProtocol {
  sealed trait CommandRequest {
    def id: UUID
    def billingId: BillingId
  }

  final case class CreateBilling(
      id: UUID,
      billingId: BillingId,
      billingItems: BillingItems,
      replyTo: ActorRef[CreateBillingReply]
  ) extends CommandRequest
  sealed trait CreateBillingReply {
    def id: UUID
    def commandRequestId: UUID
    def billingId: BillingId
  }
  case class CreateBillingSucceeded(id: UUID, commandRequestId: UUID, billingId: BillingId) extends CreateBillingReply
  case class CreateBillingFailed(id: UUID, commandRequestId: UUID, billingId: BillingId, cause: BillingError)
      extends CreateBillingReply
}
