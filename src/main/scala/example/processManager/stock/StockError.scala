package example.processManager.stock

sealed trait StockError {
  def stockId: StockId
}

object StockError {}
