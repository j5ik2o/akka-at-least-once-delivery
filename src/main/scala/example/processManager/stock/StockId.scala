package example.processManager.stock

import example.CborSerializable

import java.util.UUID

final case class StockId(value: UUID = UUID.randomUUID()) extends CborSerializable
