package example.processManager

import example.CborSerializable

object ItemPrice {
  final val zero: ItemPrice = ItemPrice(0)
}

final case class ItemPrice(private val value: Int) extends CborSerializable {

  def combine(other: ItemPrice): ItemPrice = copy(value = value + other.value)

  def toInt: Int = value

}
