package example.processManager.stock

import example.processManager.{ ItemId, ItemQuantity }

final case class StockItem(id: StockItemId, itemId: ItemId, quantity: ItemQuantity)
