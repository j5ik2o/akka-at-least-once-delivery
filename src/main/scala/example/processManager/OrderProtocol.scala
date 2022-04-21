package example.processManager

import akka.actor.typed.ActorRef
import example.processManager.OrderEffect.PersistReply
import example.processManager.billing.BillingItems
import example.processManager.billing.BillingProtocol.CreateBillingReply
import example.processManager.stock.StockItems
import example.processManager.stock.StockProtocol.{ CancelStockReply, SecureStockReply }

import java.util.UUID

object OrderProtocol {
  sealed trait CommandRequest {
    def id: UUID
    def orderId: OrderId
  }

  case class CreateOrder(id: UUID, orderId: OrderId, orderItems: OrderItems, replyTo: ActorRef[CreateOrderReply])
      extends CommandRequest
  sealed trait CreateOrderReply {
    def id: UUID
    def commandRequestId: UUID
  }
  case class CreateOrderSucceeded(id: UUID, commandRequestId: UUID, orderId: OrderId) extends CreateOrderReply
  case class CreateOrderFailed(id: UUID, commandRequestId: UUID, orderId: OrderId, error: OrderError)
      extends CreateOrderReply

  case class RetrySecureStock(
      id: UUID,
      commandRequestId: UUID,
      orderId: OrderId,
      stockItems: StockItems,
      replyTo: ActorRef[CreateOrderReply],
      attempt: Attempt
  ) extends CommandRequest
  case class WrappedSecureStockReply(
      id: UUID,
      commandRequestId: UUID,
      orderId: OrderId,
      msg: SecureStockReply,
      replyTo: ActorRef[CreateOrderReply]
  ) extends CommandRequest

  case class RetryCancelStock(
      id: UUID,
      commandRequestId: UUID,
      orderId: OrderId,
      stockItems: StockItems,
      replyTo: ActorRef[CreateOrderReply],
      attempt: Attempt
  ) extends CommandRequest

  case class WrappedCancelStockReply(
      id: UUID,
      commandRequestId: UUID,
      orderId: OrderId,
      msg: CancelStockReply,
      replyTo: ActorRef[CreateOrderReply]
  ) extends CommandRequest

  case class RetryCreateBilling(
      id: UUID,
      commandRequestId: UUID,
      orderId: OrderId,
      billingItems: BillingItems,
      replyTo: ActorRef[CreateOrderReply],
      attempt: Attempt
  ) extends CommandRequest

  case class WrappedCreateBillingReply(
      id: UUID,
      commandRequestId: UUID,
      orderId: OrderId,
      msg: CreateBillingReply,
      replyTo: ActorRef[CreateOrderReply]
  ) extends CommandRequest

  case class WrappedPersistReply(
      id: UUID,
      commandRequestId: UUID,
      orderId: OrderId,
      message: PersistReply
  ) extends CommandRequest

  case class StateRecoveryCompleted(id: UUID, commandRequestId: UUID, orderId: OrderId, state: OrderState)
      extends CommandRequest
}
