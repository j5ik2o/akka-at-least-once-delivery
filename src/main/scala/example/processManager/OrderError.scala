package example.processManager

sealed trait OrderError

object OrderError {
  case class BillingError(orderId: OrderId)     extends OrderError
  case class SecureStockError(orderId: OrderId) extends OrderError
}
