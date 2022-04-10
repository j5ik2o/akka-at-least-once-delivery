package example.processManager.billing

import example.processManager.ItemPrice

final case class BillingItems(private val head: BillingItem, private val tail: BillingItem*) {
  private val values = (head +: tail).toVector

  def totalPrice: ItemPrice = values.foldLeft(ItemPrice.zero) { (result, element) =>
    result.combine(element.itemPrice)
  }

  def toVector: Vector[BillingItem] = values
}
