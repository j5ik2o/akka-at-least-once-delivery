package example.processManager

import example.CborSerializable

import java.util.UUID

final case class OrderItemId(value: UUID = UUID.randomUUID()) extends CborSerializable
