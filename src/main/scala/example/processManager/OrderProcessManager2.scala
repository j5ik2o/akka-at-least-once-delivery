package example.processManager

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted }
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
  case class PersistSucceeded(state: OrderState) extends PersistReply
  case object PersistFailed                      extends PersistReply

  private def persistBehavior(id: OrderId, parentRef: ActorRef[OrderProtocol.CommandRequest]): Behavior[Persist] =
    Behaviors.setup { ctx =>
      EventSourcedBehavior(
        PersistenceId.ofUniqueId(id.asString),
        emptyState = OrderState.Empty(id),
        commandHandler = { (state: OrderState, command: Persist) =>
          (state, command) match {
            case (_, Persist(event, replyTo)) =>
              Effect.persist(event).thenReply(replyTo) { state: OrderState =>
                PersistSucceeded(state)
              }
          }
        },
        eventHandler = { (s: OrderState, event: OrderEvents.Event) =>
          s.applyEvent(event)
        }
      ).receiveSignal { case (state, RecoveryCompleted) =>
        parentRef ! StateRecoveryCompleted(UUID.randomUUID(), UUID.randomUUID(), id, state)
      }
    }

  private def persistEvent(id: UUID, orderId: OrderId, event: OrderEvents.Event)(
      succ: (OrderState) => Behavior[OrderProtocol.CommandRequest]
  )(implicit
      ctx: ActorContext[OrderProtocol.CommandRequest],
      persistRef: ActorRef[Persist]
  ): Behaviors.Receive[CommandRequest] = {
    val messageAdaptor = ctx.messageAdapter { msg =>
      WrappedPersistReply(UUID.randomUUID(), id, orderId, msg)
    }
    persistRef ! Persist(event, messageAdaptor)
    Behaviors.receiveMessage {
      case WrappedPersistReply(_, _, _, PersistSucceeded(state)) =>
        succ(state)
      case WrappedPersistReply(_, _, _, PersistFailed) =>
        ctx.log.error("persist failed!!!")
        Behaviors.stopped
    }
  }

  def apply(
      id: OrderId,
      backoffSettings: BackoffSettings,
      stockActorRef: ActorRef[StockProtocol.CommandRequest],
      billingActorRef: ActorRef[BillingProtocol.CommandRequest]
  ): Behavior[OrderProtocol.CommandRequest] =
    Behaviors.setup { implicit ctx =>
      Behaviors.withTimers { timers =>
        val maxAttempt: Int = maxAttemptCount(backoffSettings)

        implicit val persistRef: ActorRef[Persist] = ctx.spawn(persistBehavior(id, ctx.self), "persist")

        def billingCreating(orderState: OrderState.BillingCreating): Behavior[OrderProtocol.CommandRequest] =
          Behaviors.receiveMessage { case WrappedCreateBillingReply(_, commandRequestId, orderId, msg, replyTo) =>
            timers.cancel(commandRequestId)
            msg match {
              case CreateBillingSucceeded(_, commandRequestId, _) =>
                persistEvent(UUID.randomUUID(), orderId, OrderCommitted(UUID.randomUUID(), orderId, Instant.now())) {
                  _ =>
                    orderState.replyTo ! CreateOrderSucceeded(UUID.randomUUID(), commandRequestId, orderId)
                    Behaviors.stopped
                }
              case CreateBillingFailed(_, _, _, _) =>
                Behaviors.same
            }
          }

        def stockSecuring(orderState: OrderState.StockSecuring): Behavior[OrderProtocol.CommandRequest] =
          Behaviors.receiveMessage { case WrappedSecureStockReply(_, commandRequestId, orderId, msg, replyTo) =>
            timers.cancel(commandRequestId)
            msg match {
              case SecureStockSucceeded(_, _, _) =>
                persistEvent(commandRequestId, orderId, StockSecured(UUID.randomUUID(), orderId, Instant.now())) {
                  case state: OrderState.BillingCreating =>
                    createBilling(
                      orderId,
                      UUID.randomUUID(),
                      orderState.billingItems,
                      orderState.replyTo,
                      Attempt(2, maxAttempt)
                    )(ctx, timers, backoffSettings, billingActorRef)
                    billingCreating(state)
                }
              case SecureStockFailed(_, commandRequestId, _, error) =>
                persistEvent(commandRequestId, orderId, OrderRollbacked(UUID.randomUUID(), orderId, Instant.now())) {
                  _ =>
                    Behaviors.same
                }
            }
          }

        val empty: Behavior[OrderProtocol.CommandRequest] = Behaviors.receiveMessage {
          case CreateOrder(id, orderId, orderItems, replyTo) =>
            persistEvent(id, orderId, OrderBegan(UUID.randomUUID(), orderId, orderItems, replyTo, Instant.now())) {
              case state: OrderState.StockSecuring =>
                val stockItems = convertToStockItems(orderItems)
                secureStock(
                  orderId,
                  id,
                  stockItems,
                  replyTo,
                  Attempt(2, maxAttempt)
                )(ctx, timers, backoffSettings, stockActorRef)
                stockSecuring(state)
            }
        }

        Behaviors.withStash(32) { stashBuffer =>
          Behaviors.receiveMessage {
            case StateRecoveryCompleted(_, _, _, s: OrderState.Empty) =>
              stashBuffer.unstashAll(empty)
            case StateRecoveryCompleted(_, _, _, s: OrderState.StockSecuring) =>
              stashBuffer.unstashAll(stockSecuring(s))
            case StateRecoveryCompleted(_, _, _, s: OrderState.BillingCreating) =>
              stashBuffer.unstashAll(billingCreating(s))
            case msg =>
              stashBuffer.stash(msg)
              Behaviors.same
          }
        }
      }
    }

}
