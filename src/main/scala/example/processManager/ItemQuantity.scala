package example.processManager

import example.CborSerializable

object ItemQuantity {
  final val zero: ItemQuantity = ItemQuantity(0)
}

final case class ItemQuantity(value: Int) extends CborSerializable {

  def combine(other: ItemQuantity): ItemQuantity = copy(value = value + other.value)

  def toInt: Int = value

}
