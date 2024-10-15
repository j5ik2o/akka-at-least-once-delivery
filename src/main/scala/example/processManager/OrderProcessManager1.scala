package example.processManager

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.actor.typed.{ ActorRef, Behavior, Signal }
import akka.persistence.typed.scaladsl.EventSourcedBehavior.{ CommandHandler, EventHandler }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted }
import example.processManager.OrderError.SecureStockError
import example.processManager.OrderEvents._
import example.processManager.OrderProtocol._
import example.processManager.OrderState.{ BillingCreating, Empty, OrderRecovering, StockSecuring }
import example.processManager.billing.BillingProtocol.{
  CreateBillingFailed,
  CreateBillingReply,
  CreateBillingSucceeded
}
import example.processManager.billing._
import example.processManager.stock.StockProtocol._
import example.processManager.stock._

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.FiniteDuration

object OrderProcessManager1 {

  def apply(
      id: OrderId,
      backoffSettings: BackoffSettings,
      stockActorRef: ActorRef[StockProtocol.CommandRequest],    // 在庫アクター
      billingActorRef: ActorRef[BillingProtocol.CommandRequest] // 決済アクター
  ): Behavior[OrderProtocol.CommandRequest] = {
    Behaviors.setup { ctx =>
      val maxAttempt = maxAttemptCount(backoffSettings)
      Behaviors.withTimers { timers =>
        // プロセスマネージャーは永続化アクターである必要がある
        EventSourcedBehavior(
          PersistenceId.ofUniqueId(id.asString),
          emptyState = OrderState.Empty(id),
          commandHandler = commandHandler(ctx, timers, backoffSettings, maxAttempt, stockActorRef, billingActorRef),
          eventHandler = eventHandler(ctx)
        ).receiveSignal(signalHandler(ctx, timers, backoffSettings, maxAttempt, stockActorRef, billingActorRef))
      }
    }
  }

  private def commandHandler(implicit
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      maxAttempt: Int,
      stockActorRef: ActorRef[StockProtocol.CommandRequest],
      billingActorRef: ActorRef[BillingProtocol.CommandRequest]
  ): CommandHandler[OrderProtocol.CommandRequest, OrderEvents.Event, OrderState] = { (state, event) =>
    val pf = emptyCommandHandler
      .orElse(stockSecuringCommandHandler)
      .orElse(billingCreatingCommandHandler)
      .orElse(orderRecoveringCommandHandler)
    pf((state, event))
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

  private def signalHandler(implicit
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      maxAttempt: Int,
      stockActorRef: ActorRef[StockProtocol.CommandRequest],
      billingActorRef: ActorRef[BillingProtocol.CommandRequest]
  ): PartialFunction[(OrderState, Signal), Unit] = {
    case (s: StockSecuring, RecoveryCompleted) =>
      secureStock(
        s.orderId,
        UUID.randomUUID(),
        s.stockItems,
        s.replyTo,
        Attempt(2, maxAttempt)
      )
    case (s: BillingCreating, RecoveryCompleted) =>
      createBilling(
        s.orderId,
        UUID.randomUUID(),
        s.billingItems,
        s.replyTo,
        Attempt(2, maxAttempt)
      )
    case (s: OrderRecovering, RecoveryCompleted) =>
      cancelStock(
        s.orderId,
        UUID.randomUUID(),
        s.stockItems,
        s.replyTo,
        Attempt(2, maxAttempt)
      )
  }

  private type PartialCommandHandler[Command, Event, State] = PartialFunction[(State, Command), Effect[Event, State]]

  private def convertToStockItems(orderItems: OrderItems): StockItems = {
    def newId() = StockItemId()
    val head    = StockItem(newId(), orderItems.head.itemId, orderItems.head.itemQuantity)
    val tail    = orderItems.tail.map { it => StockItem(newId(), it.itemId, it.itemQuantity) }
    StockItems(head, tail: _*)
  }

  private def emptyCommandHandler(implicit
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      maxAttempt: Int,
      stockActorRef: ActorRef[StockProtocol.CommandRequest]
  ): PartialCommandHandler[OrderProtocol.CommandRequest, OrderEvents.Event, OrderState] = {
    case (s: Empty, CreateOrder(id, orderId, orderItems, replyTo)) if s.orderId == orderId =>
      Effect.persist(OrderBegan(UUID.randomUUID(), orderId, orderItems, replyTo, Instant.now())).thenRun { _ =>
        val stockItems = convertToStockItems(orderItems)
        secureStock(
          s.orderId,
          id,
          stockItems,
          replyTo,
          Attempt(2, maxAttempt)
        )
      }
  }

  private def stockSecuringCommandHandler(implicit
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      maxAttempt: Int,
      stockActorRef: ActorRef[StockProtocol.CommandRequest],
      billingActorRef: ActorRef[BillingProtocol.CommandRequest]
  ): PartialCommandHandler[OrderProtocol.CommandRequest, OrderEvents.Event, OrderState] = {
    case (s: StockSecuring, RetrySecureStock(_, commandRequestId, orderId, stockItems, replyTo, attempt))
        if s.orderId == orderId =>
      timers.cancel(commandRequestId)
      secureStock(
        s.orderId,
        commandRequestId,
        stockItems,
        replyTo,
        attempt.increment
      )
      Effect.none
    case (s: StockSecuring, WrappedSecureStockReply(_, commandRequestId, orderId, msg, replyTo))
        if s.orderId == orderId =>
      timers.cancel(commandRequestId)
      msg match {
        case SecureStockSucceeded(_, _, _) =>
          Effect.persist(StockSecured(UUID.randomUUID(), orderId, Instant.now())).thenRun { _ =>
            createBilling(
              s.orderId,
              UUID.randomUUID(),
              s.billingItems,
              replyTo,
              Attempt(2, maxAttempt)
            )
          }
        case SecureStockFailed(_, commandRequestId, _, error) =>
          Effect.persist(OrderRollbacked(UUID.randomUUID(), orderId, Instant.now())).thenReply(replyTo) { _ =>
            CreateOrderFailed(UUID.randomUUID(), commandRequestId, orderId, SecureStockError(orderId))
          }
      }
  }

  private def billingCreatingCommandHandler(implicit
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      maxAttempt: Int,
      stockActorRef: ActorRef[StockProtocol.CommandRequest],
      billingActorRef: ActorRef[BillingProtocol.CommandRequest]
  ): PartialCommandHandler[OrderProtocol.CommandRequest, OrderEvents.Event, OrderState] = {
    case (s: BillingCreating, RetryCreateBilling(_, commandRequestId, orderId, billingItems, replyTo, attempt))
        if s.orderId == orderId =>
      timers.cancel(commandRequestId)
      createBilling(
        s.orderId,
        commandRequestId,
        billingItems,
        replyTo,
        attempt.increment
      )
      Effect.none
    case (s: BillingCreating, WrappedCreateBillingReply(_, commandRequestId, orderId, msg, replyTo))
        if s.orderId == orderId =>
      timers.cancel(commandRequestId)
      msg match {
        case CreateBillingSucceeded(_, commandRequestId, _) =>
          Effect.persist(OrderCommitted(UUID.randomUUID(), orderId, Instant.now())).thenReply(replyTo) { _ =>
            CreateOrderSucceeded(UUID.randomUUID(), commandRequestId, orderId)
          }
        case CreateBillingFailed(_, _, _, _) =>
          Effect.persist(BillingFailed(UUID.randomUUID(), orderId, Instant.now())).thenRun { _ =>
            cancelStock(
              orderId,
              UUID.randomUUID(),
              s.stockItems,
              s.replyTo,
              Attempt(2, maxAttempt)
            )
          }
      }
    // 行き違いで返信がきた場合
    case (s: BillingCreating, cmd: WrappedSecureStockReply) if s.orderId == cmd.orderId =>
      ctx.log.warn("Illegal command: {}", cmd)
      Effect.none
  }

  private def orderRecoveringCommandHandler(implicit
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      stockActorRef: ActorRef[StockProtocol.CommandRequest]
  ): PartialCommandHandler[OrderProtocol.CommandRequest, OrderEvents.Event, OrderState] = {
    case (s: OrderRecovering, RetryCancelStock(_, commandRequestId, orderId, stockItems, replyTo, attempt))
        if s.orderId == orderId =>
      timers.cancel(commandRequestId)
      cancelStock(
        s.orderId,
        commandRequestId,
        stockItems,
        replyTo,
        attempt.increment
      )
      Effect.none
    case (s: OrderRecovering, WrappedCancelStockReply(_, commandRequestId, orderId, msg, replyTo))
        if s.orderId == orderId =>
      timers.cancel(commandRequestId)
      Effect.persist(OrderRollbacked(UUID.randomUUID(), orderId, Instant.now())).thenReply(replyTo) { _ =>
        msg match {
          case CancelStockSucceeded(_, _, _) =>
            CreateOrderFailed(UUID.randomUUID(), commandRequestId, orderId, OrderError.BillingError(orderId))
          case CancelStockFailed(_, commandRequestId, _, _) =>
            CreateOrderFailed(UUID.randomUUID(), commandRequestId, orderId, OrderError.SecureStockError(orderId))
        }
      }
    case (s: OrderRecovering, cmd: WrappedCreateBillingReply) if s.orderId == cmd.orderId =>
      ctx.log.warn("Illegal command: {}", cmd)
      Effect.none
  }

  private def cancelStock(
      orderId: OrderId,
      commandRequestId: UUID,
      stockItems: StockItems,
      replyTo: ActorRef[CreateOrderReply],
      attempt: Attempt
  )(implicit
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      stockActorRef: ActorRef[StockProtocol.CommandRequest]
  ): Unit = {
    val confirmAdapter = ctx.messageAdapter[CancelStockReply] { msg =>
      OrderProtocol.WrappedCancelStockReply(
        UUID.randomUUID(),
        commandRequestId,
        orderId,
        msg,
        replyTo
      )
    }
    stockActorRef ! StockProtocol.CancelStock(UUID.randomUUID(), StockId(), stockItems, confirmAdapter)
    timers.startSingleTimer(
      commandRequestId,
      OrderProtocol.RetryCancelStock(UUID.randomUUID(), commandRequestId, orderId, stockItems, replyTo, attempt),
      exponentialBackOff(attempt, backoffSettings)
    )
  }

  def secureStock(
      orderId: OrderId,
      commandRequestId: UUID,
      stockItems: StockItems,
      replyTo: ActorRef[CreateOrderReply],
      attempt: Attempt
  )(implicit
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      stockActorRef: ActorRef[StockProtocol.CommandRequest]
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

  def createBilling(
      orderId: OrderId,
      commandRequestId: UUID,
      billingItems: BillingItems,
      replyTo: ActorRef[OrderProtocol.CreateOrderReply],
      attempt: Attempt
  )(implicit
      ctx: ActorContext[OrderProtocol.CommandRequest],
      timers: TimerScheduler[OrderProtocol.CommandRequest],
      backoffSettings: BackoffSettings,
      billingActorRef: ActorRef[BillingProtocol.CommandRequest]
  ): Unit = {
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

  def maxAttemptCount(
      backoffSettings: BackoffSettings
  ): Int = {
    import backoffSettings._
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
    import backoffSettings._
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    if (restartCount.toInt >= 30) // Duration overflow protection (> 100 years)
      maxBackoff
    else
      maxBackoff.min(minBackoff * math.pow(2, restartCount.toDouble)) * rnd match {
        case f: FiniteDuration => f
        case _                 => maxBackoff
      }
  }

}
