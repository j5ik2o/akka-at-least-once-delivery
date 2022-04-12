package example.processManager.stock

import example.CborSerializable

import java.util.UUID

final case class StockItemId(value: UUID = UUID.randomUUID()) extends CborSerializable
