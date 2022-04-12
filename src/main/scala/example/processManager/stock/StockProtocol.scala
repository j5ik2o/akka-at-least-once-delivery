package example.processManager.stock

import akka.actor.typed.ActorRef

import java.util.UUID

object StockProtocol {
  sealed trait CommandRequest {
    def id: UUID
    def stockId: StockId
  }

  final case class SecureStock(id: UUID, stockId: StockId, stockItems: StockItems, replyTo: ActorRef[SecureStockReply])
      extends CommandRequest
  sealed trait SecureStockReply {
    def id: UUID
    def commandRequestId: UUID
    def stockId: StockId
  }
  final case class SecureStockSucceeded(id: UUID, commandRequestId: UUID, stockId: StockId) extends SecureStockReply
  final case class SecureStockFailed(id: UUID, commandRequestId: UUID, stockId: StockId, error: StockError)
      extends SecureStockReply

  final case class CancelStock(id: UUID, stockId: StockId, stockItems: StockItems, replyTo: ActorRef[CancelStockReply])
      extends CommandRequest
  sealed trait CancelStockReply {
    def id: UUID
    def commandRequestId: UUID
    def stockId: StockId
  }
  final case class CancelStockSucceeded(id: UUID, commandRequestId: UUID, stockId: StockId) extends CancelStockReply
  final case class CancelStockFailed(id: UUID, commandRequestId: UUID, stockId: StockId, error: StockError)
      extends CancelStockReply
}
