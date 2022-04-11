package example.processManager

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior.{ CommandHandler, EventHandler }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import example.processManager.OrderError.SecureStockError
import example.processManager.OrderEvents._
import example.processManager.OrderProtocol._
import example.processManager.OrderState.{ BillingCreating, Empty, OrderRecovering, StockSecuring }
import example.processManager.billing.BillingProtocol.CreateBillingReply
import example.processManager.billing._
import example.processManager.stock.StockProtocol.{ SecureStockFailed, SecureStockReply, SecureStockSucceeded }
import example.processManager.stock._

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.FiniteDuration

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

object OrderActor {

  private def maxAttemptCount(
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration
  ): Int = {
    require(maxBackoff < minBackoff * math.pow(2.0, 30), "maxBackoffが大きすぎます")
    LazyList
      .from(1).map { n =>
        (n, minBackoff * math.pow(2.0, n))
      }.find(_._2 > maxBackoff).map(_._1).getOrElse(0)
  }

  private def exponentialBackOff(
      restartCount: Attempt,
      backoffSettings: BackoffSettings
  ): FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * backoffSettings.randomFactor
    if (restartCount.toInt >= 30) // Duration overflow protection (> 100 years)
      backoffSettings.maxBackoff
    else
      backoffSettings.maxBackoff.min(backoffSettings.minBackoff * math.pow(2, restartCount.toDouble)) * rnd match {
        case f: FiniteDuration => f
        case _                 => backoffSettings.maxBackoff
      }
  }

  def apply(
      id: OrderId,
      backoffSettings: BackoffSettings,
      stockActorRef: ActorRef[StockProtocol.CommandRequest],
      billingActorRef: ActorRef[BillingProtocol.CommandRequest]
  ): Behavior[OrderProtocol.CommandRequest] = {
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        EventSourcedBehavior(
          PersistenceId.ofUniqueId(id.asString),
          emptyState = OrderState.Empty(id),
          commandHandler = commandHandler(ctx, timers, backoffSettings, stockActorRef, billingActorRef),
          eventHandler = eventHandler(ctx)
        )
      }
    }
  }
  private def convertToStockItems(orderItems: OrderItems): StockItems = {
    def newId() = StockItemId()
    val head    = StockItem(newId(), orderItems.head.itemId, orderItems.head.itemQuantity)
    val tail    = orderItems.tail.map { it => StockItem(newId(), it.itemId, it.itemQuantity) }
    StockItems(head, tail: _*)
  }
  private def commandHandler(
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      stockActorRef: ActorRef[StockProtocol.CommandRequest],
      billingActorRef: ActorRef[BillingProtocol.CommandRequest]
  ): CommandHandler[OrderProtocol.CommandRequest, OrderEvents.Event, OrderState] = { (state, command) =>
    (state, command) match {
      case (s: Empty, CreateOrder(id, orderId, orderItems, replyTo)) if s.orderId == orderId =>
        Effect.persist(OrderBegan(UUID.randomUUID(), orderId, orderItems, replyTo, Instant.now())).thenRun { s =>
          val stockItems = convertToStockItems(orderItems)
          secureStock(
            ctx,
            timers,
            backoffSettings,
            orderId,
            stockActorRef,
            id,
            stockItems,
            replyTo,
            Attempt(2, 10)
          )
        }
      case (s: StockSecuring, RetrySecureStock(_, commandRequestId, orderId, stockItems, replyTo, attempt))
          if s.orderId == orderId =>
        timers.cancel(commandRequestId)
        secureStock(
          ctx,
          timers,
          backoffSettings,
          orderId,
          stockActorRef,
          commandRequestId,
          stockItems,
          replyTo,
          attempt
        )
        Effect.none
      case (s: StockSecuring, WrappedSecureStockReply(_, commandRequestId, orderId, msg, replyTo)) =>
        timers.cancel(commandRequestId)
        msg match {
          case SecureStockSucceeded(_, _, _) =>
            Effect.persist(StockSecured(UUID.randomUUID(), orderId, Instant.now())).thenRun { state =>
              createBilling(
                ctx,
                timers,
                backoffSettings,
                orderId,
                billingActorRef,
                UUID.randomUUID(),
                s.billingItems,
                replyTo,
                Attempt(2, 10)
              )
            }
          case SecureStockFailed(_, commandRequestId, _, error) =>
            Effect.persist(OrderRollbacked(UUID.randomUUID(), orderId, Instant.now())).thenReply(replyTo) { _ =>
              CreateOrderFailed(UUID.randomUUID(), commandRequestId, orderId, SecureStockError(orderId))
            }
        }
    }
  }

  private def secureStock(
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      orderId: OrderId,
      stockActorRef: ActorRef[StockProtocol.CommandRequest],
      commandRequestId: UUID,
      stockItems: StockItems,
      replyTo: ActorRef[CreateOrderReply],
      attempt: Attempt
  ): Unit = {
    val confirmAdapter =
      ctx.messageAdapter[SecureStockReply] { msg =>
        OrderProtocol.WrappedSecureStockReply(
          UUID.randomUUID(),
          commandRequestId,
          orderId,
          msg,
          replyTo
        )
      }
    stockActorRef ! StockProtocol.SecureStock(UUID.randomUUID(), StockId(), stockItems, confirmAdapter)
    timers.startSingleTimer(
      commandRequestId,
      OrderProtocol.RetrySecureStock(UUID.randomUUID(), commandRequestId, orderId, stockItems, replyTo, attempt),
      exponentialBackOff(attempt, backoffSettings)
    )
  }

  private def createBilling(
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      orderId: OrderId,
      billingActorRef: ActorRef[BillingProtocol.CommandRequest],
      commandRequestId: UUID,
      billingItems: BillingItems,
      replyTo: ActorRef[OrderProtocol.CreateOrderReply],
      attempt: Attempt
  ) = {
    val confirmAdapter =
      ctx.messageAdapter[CreateBillingReply] { msg =>
        OrderProtocol.WrappedCreateBillingReply(
          UUID.randomUUID(),
          commandRequestId,
          orderId,
          msg,
          replyTo
        )
      }
    billingActorRef ! BillingProtocol.CreateBilling(commandRequestId, BillingId(), billingItems, confirmAdapter)
    timers.startSingleTimer(
      commandRequestId,
      OrderProtocol.RetryCreateBilling(UUID.randomUUID(), commandRequestId, orderId, billingItems, replyTo, attempt),
      exponentialBackOff(attempt, backoffSettings)
    )
  }

  private def eventHandler(
      ctx: ActorContext[OrderProtocol.CommandRequest]
  ): EventHandler[OrderState, OrderEvents.Event] = { (state, event) =>
    (state, event) match {
      case (s: Empty, OrderBegan(_, orderId, orderItems, replyTo, _)) if s.orderId == orderId =>
        s.stockSecuring(orderItems, replyTo)
      case (s: StockSecuring, StockSecured(_, orderId, _)) if s.orderId == orderId =>
        s.billingCreating
      case (s: BillingCreating, OrderCommitted(_, orderId, _)) if s.orderId == orderId =>
        s
      case (s: BillingCreating, BillingFailed(_, orderId, _)) if s.orderId == orderId =>
        s.recovering
      case (s: OrderRecovering, OrderRollbacked(_, orderId, _)) if s.orderId == orderId =>
        s
    }
  }
}
