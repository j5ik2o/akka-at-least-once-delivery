package example.processManager.stock

import example.CborSerializable
import example.processManager.ItemQuantity

final case class StockItems(head: StockItem, tail: StockItem*) extends CborSerializable {
  private val values = (head +: tail).toVector

  def totalQuantity: ItemQuantity = values.foldLeft(ItemQuantity.zero) { (result, element) =>
    result.combine(element.quantity)
  }

  def toVector: Vector[StockItem] = values
}
