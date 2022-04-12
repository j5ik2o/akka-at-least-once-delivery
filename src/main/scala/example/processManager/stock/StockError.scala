package example.processManager.stock

import example.CborSerializable

sealed trait StockError extends CborSerializable {
  def stockId: StockId
}

object StockError {}
