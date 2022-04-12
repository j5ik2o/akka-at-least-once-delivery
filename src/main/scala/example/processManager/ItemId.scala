package example.processManager

import example.CborSerializable

import java.util.UUID

final case class ItemId(value: UUID = UUID.randomUUID()) extends CborSerializable
