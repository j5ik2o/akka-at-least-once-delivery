package example.processManager

import akka.actor.typed.ActorRef
import example.processManager.stock.StockItems

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
  case class CreateOrderFailed(id: UUID, commandRequestId: UUID, orderId: OrderId)    extends CreateOrderReply

  case class RetrySecureStock(
      id: UUID,
      commandRequestId: UUID,
      orderId: OrderId,
      stockItems: StockItems,
      replyTo: ActorRef[CreateOrderReply]
  )

}
