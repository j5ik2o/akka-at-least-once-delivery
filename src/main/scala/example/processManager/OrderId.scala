package example.processManager

final case class OrderId(value: Int) {
  def asString: String = value.toString
}
