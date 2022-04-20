package example.processManager

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import example.processManager.OrderEvents._
import example.processManager.OrderProcessManager.{ convertToStockItems, createBilling, maxAttemptCount, secureStock }
import example.processManager.OrderProtocol._
import example.processManager.billing.BillingProtocol.{ CreateBillingFailed, CreateBillingSucceeded }
import example.processManager.billing._
import example.processManager.stock.StockProtocol.{ SecureStockFailed, SecureStockSucceeded }
import example.processManager.stock._

import java.time.Instant
import java.util.UUID

object OrderProcessManager2 {

  case class Persist(event: OrderEvents.Event, replyTo: ActorRef[PersistReply])
  sealed trait PersistReply
  case object PersistSucceeded extends PersistReply
  case object PersistFailed    extends PersistReply

  // TODO: 永続化アクターを実装する
  def persist: Behavior[Persist] = ???

  def apply(
      orderId: OrderId,
      stockActorRef: ActorRef[StockProtocol.CommandRequest],
      billingActorRef: ActorRef[BillingProtocol.CommandRequest],
      backoffSettings: BackoffSettings
  ): Behavior[OrderProtocol.CommandRequest] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val maxAttempt = maxAttemptCount(backoffSettings)
        val persistRef = ctx.spawn(persist, "persist")

        def persistEvent(id: UUID, orderId: OrderId, event: OrderEvents.Event): Unit = {
          val messageAdaptor = ctx.messageAdapter { msg =>
            WrappedPersistReply(UUID.randomUUID(), id, orderId, msg)
          }
          persistRef ! Persist(event, messageAdaptor)
        }

        def billingCreatingPersisted(orderState: OrderState.BillingCreating): Behavior[OrderProtocol.CommandRequest] =
          Behaviors.receiveMessage { case WrappedPersistReply(_, commandRequestId, orderId, msg) =>
            orderState.replyTo ! CreateOrderSucceeded(UUID.randomUUID(), commandRequestId, orderId)
            Behaviors.same
          }

        def billingCreating(orderState: OrderState.BillingCreating): Behavior[OrderProtocol.CommandRequest] =
          Behaviors.receiveMessage { case WrappedCreateBillingReply(_, commandRequestId, orderId, msg, replyTo) =>
            timers.cancel(commandRequestId)
            msg match {
              case CreateBillingSucceeded(_, commandRequestId, _) =>
                persistEvent(UUID.randomUUID(), orderId, OrderCommitted(UUID.randomUUID(), orderId, Instant.now()))
                billingCreatingPersisted(orderState)
              case CreateBillingFailed(_, _, _, _) =>
//                Effect.persist(BillingFailed(UUID.randomUUID(), orderId, Instant.now())).thenRun { _ =>
//                  cancelStock(
//                    orderId,
//                    UUID.randomUUID(),
//                    s.stockItems,
//                    s.replyTo,
//                    Attempt(2, maxAttempt)
//                  )
                Behaviors.same
            }
          }

        def stockSecuringPersisted(
            orderState: OrderState.StockSecuring
        ): Behavior[OrderProtocol.CommandRequest] =
          Behaviors.receiveMessage { case WrappedPersistReply(_, commandRequestId, orderId, msg) =>
            createBilling(
              orderId,
              UUID.randomUUID(),
              orderState.billingItems,
              orderState.replyTo,
              Attempt(2, maxAttempt)
            )(ctx, timers, backoffSettings, billingActorRef)
            billingCreating(orderState.billingCreating)
          }

        def stockSecuring(orderState: OrderState.StockSecuring): Behavior[OrderProtocol.CommandRequest] =
          Behaviors.receiveMessage { case WrappedSecureStockReply(_, commandRequestId, orderId, msg, replyTo) =>
            timers.cancel(commandRequestId)
            msg match {
              case SecureStockSucceeded(_, _, _) =>
                persistEvent(commandRequestId, orderId, StockSecured(UUID.randomUUID(), orderId, Instant.now()))
                stockSecuringPersisted(orderState)
              case SecureStockFailed(_, commandRequestId, _, error) =>
//                Effect.persist(OrderRollbacked(UUID.randomUUID(), orderId, Instant.now())).thenReply(replyTo) { _ =>
//                  CreateOrderFailed(UUID.randomUUID(), commandRequestId, orderId, SecureStockError(orderId))
//                }
            }
            Behaviors.same
          }

        def emptyPersisted(
            orderItems: OrderItems,
            replyTo: ActorRef[CreateOrderReply]
        ): Behavior[OrderProtocol.CommandRequest] =
          Behaviors.receiveMessage { case WrappedPersistReply(_, commandRequestId, orderId, msg) =>
            val stockItems = convertToStockItems(orderItems)
            secureStock(
              orderId,
              commandRequestId,
              stockItems,
              replyTo,
              Attempt(2, maxAttempt)
            )(ctx, timers, backoffSettings, stockActorRef)
            stockSecuring(OrderState.Empty(orderId).stockSecuring(orderItems, replyTo))
          }

        val empty: Behavior[OrderProtocol.CommandRequest] = Behaviors.receiveMessage {
          case CreateOrder(id, orderId, orderItems, replyTo) =>
            persistEvent(id, orderId, OrderBegan(UUID.randomUUID(), orderId, orderItems, replyTo, Instant.now()))
            emptyPersisted(orderItems, replyTo)
        }

        empty
      }
    }

}
