package example.processManager.billing

import example.CborSerializable

import java.util.UUID

final case class BillingId(value: UUID = UUID.randomUUID()) extends CborSerializable
