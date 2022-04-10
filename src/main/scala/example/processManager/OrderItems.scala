package example.processManager

final case class OrderItems(head: OrderItem, tail: OrderItem*) {

  private val values: Vector[OrderItem] = (head +: tail).toVector

  def totalPrice(): ItemPrice = values.foldLeft(ItemPrice.zero) { (result, element) =>
    result.combine(element.itemPrice)
  }

  def toVector: Vector[OrderItem] = values

}
