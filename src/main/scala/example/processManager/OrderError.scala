package example.processManager

import example.CborSerializable

sealed trait OrderError extends CborSerializable

object OrderError {
  case class BillingError(orderId: OrderId) extends OrderError
  case class SecureStockError(orderId: OrderId) extends OrderError
}
