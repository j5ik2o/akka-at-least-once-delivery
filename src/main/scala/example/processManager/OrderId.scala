package example.processManager

import example.CborSerializable

import java.util.UUID

final case class OrderId(value: UUID = UUID.randomUUID()) extends CborSerializable {
  def asString: String = value.toString
}
