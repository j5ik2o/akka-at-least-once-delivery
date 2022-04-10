package example.processManager.billing

import example.processManager.CborSerializable

import java.time.Instant
import java.util.UUID

object BillingEvents {
  sealed trait Event extends CborSerializable {
    def id: UUID
    def billingId: BillingId
    def occurredAt: Instant
  }

  final case class BillingCreated(id: UUID, billingId: BillingId, occurredAt: Instant)  extends Event
  final case class BillingApproved(id: UUID, billingId: BillingId, occurredAt: Instant) extends Event
}
