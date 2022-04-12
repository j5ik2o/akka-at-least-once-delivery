package example.processManager.stock

import example.CborSerializable
import example.processManager.{ ItemId, ItemQuantity }

final case class StockItem(id: StockItemId, itemId: ItemId, quantity: ItemQuantity) extends CborSerializable
