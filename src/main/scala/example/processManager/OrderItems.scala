package example.processManager

import example.CborSerializable

final case class OrderItems(head: OrderItem, tail: OrderItem*) extends CborSerializable {

  private val values: Vector[OrderItem] = (head +: tail).toVector

  def totalPrice(): ItemPrice = values.foldLeft(ItemPrice.zero) { (result, element) =>
    result.combine(element.itemPrice)
  }

  def toVector: Vector[OrderItem] = values

}
