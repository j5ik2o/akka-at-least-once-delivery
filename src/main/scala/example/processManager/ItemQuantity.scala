package example.processManager

object ItemQuantity {
  final val zero: ItemQuantity = ItemQuantity(0)
}

final case class ItemQuantity(value: Int) {

  def combine(other: ItemQuantity): ItemQuantity = copy(value = value + other.value)

  def toInt: Int = value

}
