package example.processManager

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer, TimerScheduler }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted }
import example.processManager.OrderEffect.Persist
import example.processManager.OrderEvents._
import example.processManager.OrderProtocol._
import example.processManager.billing.BillingProtocol.{
  CreateBillingFailed,
  CreateBillingReply,
  CreateBillingSucceeded
}
import example.processManager.billing._
import example.processManager.stock._

import java.time.Instant
import java.util.UUID
import java.util.concurrent.{ ConcurrentHashMap, ThreadLocalRandom }
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

object OrderEffect {
  case class Persist(event: OrderEvents.Event, replyTo: ActorRef[PersistReply])
  sealed trait PersistReply
  case class PersistCompleted(state: OrderState) extends PersistReply

  private val persistStates: scala.collection.mutable.Map[OrderId, Option[OrderState]] =
    new ConcurrentHashMap[OrderId, Option[OrderState]]().asScala

  // akka-persistenceを抽象化したアクター(テスト用途)
  def persistInMemoryBehavior(
      id: OrderId,
      parentRef: ActorRef[OrderProtocol.CommandRequest]
  ): Behavior[Persist] = {
    Behaviors.setup { ctx =>
      val initialState = persistStates.getOrElseUpdate(id, None) match {
        case Some(state) =>
          parentRef ! StateRecoveryCompleted(UUID.randomUUID(), UUID.randomUUID(), id, state)
          state
        case None =>
          val state = OrderState.Empty(id)
          parentRef ! StateRecoveryCompleted(UUID.randomUUID(), UUID.randomUUID(), id, state)
          state
      }
      def state(s: OrderState): Behavior[Persist] = {
        Behaviors
          .receiveMessage { case Persist(event, replyTo) =>
            val newState = s.applyEvent(event)
            replyTo ! PersistCompleted(newState)
            persistStates.put(id, Some(newState))
            state(newState)
          }
      }
      state(initialState)
    }
  }

  def persistBehavior(id: OrderId, parentRef: ActorRef[OrderProtocol.CommandRequest]): Behavior[Persist] =
    Behaviors.setup { ctx =>
      EventSourcedBehavior(
        PersistenceId.ofUniqueId(id.asString),
        emptyState = OrderState.Empty(id),
        commandHandler = { (state: OrderState, command: Persist) =>
          (state, command) match {
            case (_, Persist(event, replyTo)) =>
              Effect.persist(event).thenReply(replyTo) { state: OrderState =>
                PersistCompleted(state)
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

  def persist(id: UUID, orderId: OrderId, event: OrderEvents.Event)(
      succ: (OrderState) => Behavior[OrderProtocol.CommandRequest]
  )(implicit
      ctx: ActorContext[OrderProtocol.CommandRequest],
      persistRef: ActorRef[Persist]
  ): Behaviors.Receive[CommandRequest] = {
    val messageAdaptor = ctx.messageAdapter[PersistReply] { msg =>
      WrappedPersistReply(UUID.randomUUID(), id, orderId, msg)
    }
    persistRef ! Persist(event, messageAdaptor)
    Behaviors.receiveMessagePartial { case WrappedPersistReply(_, _, _, PersistCompleted(state)) =>
      succ(state)
    }
  }

}

object OrderProcessManager2 {

  def apply(
      id: OrderId,
      backoffSettings: BackoffSettings,
      stockActorRef: ActorRef[StockProtocol.CommandRequest],
      billingActorRef: ActorRef[BillingProtocol.CommandRequest],
      persistBehaviorF: (OrderId, ActorRef[CommandRequest]) => Behavior[Persist] = OrderEffect.persistBehavior
  ): Behavior[OrderProtocol.CommandRequest] =
    Behaviors.setup { implicit ctx =>
      Behaviors.withTimers { timers =>
        val maxAttempt: Int = maxAttemptCount(backoffSettings)

        implicit val persistRef: ActorRef[OrderEffect.Persist] =
          ctx.spawn(persistBehaviorF(id, ctx.self), "persist")

        def stockRecovering(orderState: OrderState.OrderRecovering): Behavior[OrderProtocol.CommandRequest] = {
          Behaviors.receiveMessage {
            case message: WrappedCreateBillingReply =>
              ctx.log.warn(s"Illegal message: $message")
              Behaviors.same
            case RetryCancelStock(_, commandRequestId, orderId, stockItems, replyTo, attempt) =>
              timers.cancel(commandRequestId)
              cancelStock(
                orderId,
                commandRequestId,
                stockItems,
                replyTo,
                attempt.increment
              )(ctx, timers, backoffSettings, stockActorRef)
              Behaviors.same
            case WrappedCancelStockReply(_, commandRequestId, orderId, msg, _) =>
              timers.cancel(commandRequestId)
              OrderEffect.persist(
                UUID.randomUUID(),
                orderId,
                OrderRollbacked(UUID.randomUUID(), orderId, Instant.now())
              ) { _ =>
                msg match {
                  case StockProtocol.CancelStockSucceeded(_, _, _) =>
                    orderState.replyTo ! CreateOrderFailed(
                      UUID.randomUUID(),
                      commandRequestId,
                      orderId,
                      OrderError.BillingError(orderId)
                    )
                    Behaviors.stopped
                  case StockProtocol.CancelStockFailed(_, commandRequestId, _, _) =>
                    orderState.replyTo ! CreateOrderFailed(
                      UUID.randomUUID(),
                      commandRequestId,
                      orderId,
                      OrderError.SecureStockError(orderId)
                    )
                    Behaviors.stopped
                }
              }
          }
        }

        def billingCreating(orderState: OrderState.BillingCreating): Behavior[OrderProtocol.CommandRequest] =
          Behaviors.receiveMessage {
            case message: WrappedSecureStockReply =>
              ctx.log.warn(s"Illegal message: $message")
              Behaviors.same
            case RetryCreateBilling(_, commandRequestId, orderId, billingItems, replyTo, attempt) =>
              timers.cancel(commandRequestId)
              createBilling(
                orderId,
                commandRequestId,
                billingItems,
                replyTo,
                attempt.increment
              )(ctx, timers, backoffSettings, billingActorRef)
              Behaviors.same
            case WrappedCreateBillingReply(_, commandRequestId, orderId, msg, replyTo) =>
              timers.cancel(commandRequestId)
              msg match {
                case CreateBillingSucceeded(_, commandRequestId, _) =>
                  OrderEffect.persist(
                    UUID.randomUUID(),
                    orderId,
                    OrderCommitted(UUID.randomUUID(), orderId, Instant.now())
                  ) { _ =>
                    orderState.replyTo ! CreateOrderSucceeded(UUID.randomUUID(), commandRequestId, orderId)
                    Behaviors.stopped
                  }
                case CreateBillingFailed(_, _, _, _) =>
                  OrderEffect.persist(
                    UUID.randomUUID(),
                    orderId,
                    BillingFailed(UUID.randomUUID(), orderId, Instant.now())
                  ) { case state: OrderState.OrderRecovering =>
                    cancelStock(
                      orderId,
                      UUID.randomUUID(),
                      orderState.stockItems,
                      orderState.replyTo,
                      Attempt(2, maxAttempt)
                    )(ctx, timers, backoffSettings, stockActorRef)
                    stockRecovering(state)
                  }
              }
          }

        def stockSecuring(orderState: OrderState.StockSecuring): Behavior[OrderProtocol.CommandRequest] =
          Behaviors.receiveMessage {
            case RetrySecureStock(_, commandRequestId, orderId, stockItems, replyTo, attempt) =>
              timers.cancel(commandRequestId)
              secureStock(
                orderId,
                commandRequestId,
                stockItems,
                replyTo,
                attempt.increment
              )(ctx, timers, backoffSettings, stockActorRef)
              Behaviors.same
            case WrappedSecureStockReply(_, commandRequestId, orderId, msg, replyTo) =>
              timers.cancel(commandRequestId)
              msg match {
                case StockProtocol.SecureStockSucceeded(_, _, _) =>
                  OrderEffect.persist(
                    commandRequestId,
                    orderId,
                    StockSecured(UUID.randomUUID(), orderId, Instant.now())
                  ) { case state: OrderState.BillingCreating =>
                    createBilling(
                      orderId,
                      UUID.randomUUID(),
                      orderState.billingItems,
                      orderState.replyTo,
                      Attempt(2, maxAttempt)
                    )(ctx, timers, backoffSettings, billingActorRef)
                    billingCreating(state)
                  }
                case StockProtocol.SecureStockFailed(_, commandRequestId, _, error) =>
                  OrderEffect.persist(
                    commandRequestId,
                    orderId,
                    OrderRollbacked(UUID.randomUUID(), orderId, Instant.now())
                  ) { _ =>
                    Behaviors.same
                  }
              }
          }

        def empty(s: OrderState.Empty): Behavior[OrderProtocol.CommandRequest] = Behaviors.receiveMessage {
          case CreateOrder(id, orderId, orderItems, replyTo) =>
            OrderEffect.persist(
              id,
              orderId,
              OrderBegan(UUID.randomUUID(), orderId, orderItems, replyTo, Instant.now())
            ) { case state: OrderState.StockSecuring =>
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

        def replayHandler(
            stashBuffer: StashBuffer[OrderProtocol.CommandRequest]
        ): Behaviors.Receive[CommandRequest] =
          Behaviors.receiveMessagePartial {
            case StateRecoveryCompleted(_, _, _, s: OrderState.Empty) =>
              stashBuffer.unstashAll(empty(s))
            case StateRecoveryCompleted(_, _, _, s: OrderState.StockSecuring) =>
              secureStock(
                s.orderId,
                UUID.randomUUID(),
                s.stockItems,
                s.replyTo,
                Attempt(2, maxAttempt)
              )(ctx, timers, backoffSettings, stockActorRef)
              stashBuffer.unstashAll(stockSecuring(s))
            case StateRecoveryCompleted(_, _, _, s: OrderState.BillingCreating) =>
              createBilling(
                s.orderId,
                UUID.randomUUID(),
                s.billingItems,
                s.replyTo,
                Attempt(2, maxAttempt)
              )(ctx, timers, backoffSettings, billingActorRef)
              stashBuffer.unstashAll(billingCreating(s))
            case StateRecoveryCompleted(_, _, _, s: OrderState.OrderRecovering) =>
              cancelStock(
                s.orderId,
                UUID.randomUUID(),
                s.stockItems,
                s.replyTo,
                Attempt(2, maxAttempt)
              )(ctx, timers, backoffSettings, stockActorRef)
              stashBuffer.unstashAll(stockRecovering(s))
            case msg =>
              stashBuffer.stash(msg)
              Behaviors.same
          }

        Behaviors.withStash(32) { stashBuffer =>
          replayHandler(stashBuffer)
        }
      }
    }

  def convertToStockItems(orderItems: OrderItems): StockItems = {
    def newId() = StockItemId()
    val head    = StockItem(newId(), orderItems.head.itemId, orderItems.head.itemQuantity)
    val tail    = orderItems.tail.map { it => StockItem(newId(), it.itemId, it.itemQuantity) }
    StockItems(head, tail: _*)
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
      ctx.messageAdapter[StockProtocol.SecureStockReply] { msg =>
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
    val confirmAdapter = ctx.messageAdapter[StockProtocol.CancelStockReply] { msg =>
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
