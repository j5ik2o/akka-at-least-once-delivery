package example.processManager.stock

import example.processManager.ItemQuantity

final case class StockItems(head: StockItem, tail: StockItem*) {
  private val values = (head +: tail).toVector

  def totalQuantity: ItemQuantity = values.foldLeft(ItemQuantity.zero) { (result, element) =>
    result.combine(element.quantity)
  }

  def toVector: Vector[StockItem] = values
}
