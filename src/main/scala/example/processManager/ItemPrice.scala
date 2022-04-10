package example.processManager

object ItemPrice {
  final val zero: ItemPrice = ItemPrice(0)
}

final case class ItemPrice(private val value: Int) {

  def combine(other: ItemPrice): ItemPrice = copy(value = value + other.value)

  def toInt: Int = value

}
