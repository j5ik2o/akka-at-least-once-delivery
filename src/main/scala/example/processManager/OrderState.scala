package example.processManager

import akka.actor.typed.ActorRef
import example.processManager.billing.{ BillingItem, BillingItemId, BillingItems }
import example.processManager.stock.{ StockItem, StockItemId, StockItems }

sealed trait OrderState

object OrderState {

  final case class Empty(orderId: OrderId) extends OrderState {
    def stockSecuring(orderItems: OrderItems, replyTo: ActorRef[OrderProtocol.CreateOrderReply]): StockSecuring =
      StockSecuring(orderId, orderItems, replyTo)
  }

  abstract class Base(orderId: OrderId, orderItems: OrderItems, replyTo: ActorRef[OrderProtocol.CreateOrderReply])
      extends OrderState {
    def stockItems: StockItems = {
      def newId() = StockItemId()
      val head    = StockItem(newId(), orderItems.head.itemId, orderItems.head.itemQuantity)
      val tail    = orderItems.tail.map { it => StockItem(newId(), it.itemId, it.itemQuantity) }
      StockItems(head, tail: _*)
    }

    def billingItems: BillingItems = {
      def newId() = BillingItemId()
      val head    = BillingItem(newId(), orderItems.head.itemId, orderItems.head.itemPrice)
      val tail    = orderItems.tail.map { it => BillingItem(newId(), it.itemId, it.itemPrice) }
      BillingItems(head, tail: _*)
    }
  }

  final case class StockSecuring(
      orderId: OrderId,
      orderItems: OrderItems,
      replyTo: ActorRef[OrderProtocol.CreateOrderReply]
  ) extends Base(orderId, orderItems, replyTo) {
    def billingCreating: BillingCreating = BillingCreating(orderId, orderItems, replyTo)
  }

  final case class BillingCreating(
      orderId: OrderId,
      orderItems: OrderItems,
      replyTo: ActorRef[OrderProtocol.CreateOrderReply]
  ) extends Base(orderId, orderItems, replyTo) {
    def recovering: OrderRecovering = OrderRecovering(orderId, orderItems, replyTo)
  }

  final case class OrderRecovering(
      orderId: OrderId,
      orderItems: OrderItems,
      replyTo: ActorRef[OrderProtocol.CreateOrderReply]
  ) extends Base(orderId, orderItems, replyTo)
}
