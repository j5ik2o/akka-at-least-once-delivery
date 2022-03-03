package example.saga

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import akka.persistence.typed.scaladsl.EventSourcedBehavior.{ CommandHandler, EventHandler }
import example.CborSerializable
import example.saga.OrderActor.CreateOrderSucceeded

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

object OrderActor {
  sealed trait Command
  case class CreateOrder(id: UUID, itemId: UUID, num: Int, orderAt: Instant, replyTo: ActorRef[CreateOrderReply])
      extends Command
  sealed trait CreateOrderReply
  case class CreateOrderSucceeded(requestId: UUID) extends CreateOrderReply
  case class CreateOrderFailed(requestId: UUID)    extends CreateOrderReply

  def apply: Behavior[Command] = { ??? }
}

object PaymentActor {
  sealed trait Command
  def apply: Behavior[Command] = { ??? }
}

object InventoryActor {
  sealed trait Command
  def apply: Behavior[Command] = { ??? }
}

object DeliveryActor {
  sealed trait Command
  def apply: Behavior[Command] = { ??? }
}

object OrderSaga {
  // --- コマンド
  sealed trait Command

  case class Execute(id: UUID, itemId: UUID, num: Int, replyTo: ActorRef[ExecuteReply]) extends Command
  sealed trait ExecuteReply
  case class ExecuteSucceeded(requestId: UUID) extends ExecuteReply
  case class ExecuteFailed(requestId: UUID)    extends ExecuteReply

  private case class WrappedCreateOrderReply(msg: OrderActor.CreateOrderReply) extends Command
  private case class CreateOrderRetry(id: UUID, attempt: Int)                  extends Command

  // --- イベント
  sealed trait Event extends CborSerializable

  final case class CreateOrderSent(id: UUID, itemId: UUID, num: Int, replyTo: ActorRef[ExecuteReply]) extends Event
  final case class CreateOrderSucceeded(requestId: UUID)                                              extends Event
  final case class CreateOrderFailed(requestId: UUID)                                                 extends Event

  final case class ProcessPaymentSent(id: UUID) extends Event

  private case class State() extends CborSerializable

  case object EmptyState extends State
  case class OrderDetail(itemId: UUID, num: Int)
  case class JustState(pendingOrders: Map[UUID, OrderDetail], replyTo: ActorRef[ExecuteReply]) extends State

  def apply(
      id: UUID,
      orderActorRef: ActorRef[OrderActor.Command],
      paymentActorRef: ActorRef[PaymentActor.Command],
      inventoryActorRef: ActorRef[InventoryActor.Command],
      deliveryActorRef: ActorRef[DeliveryActor.Command],
      requestTimeout: FiniteDuration
  ): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        EventSourcedBehavior[Command, Event, State](
          PersistenceId.ofUniqueId(id.toString), // ID
          emptyState = EmptyState,               // 初期状態
          commandHandler(
            context,
            timers,
            orderActorRef,
            paymentActorRef,
            inventoryActorRef,
            deliveryActorRef,
            requestTimeout
          ),           // コマンドハンドラ
          eventHandler // イベントハンドラ
        )
      }
    }
  }

  private def commandHandler(
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      orderActorRef: ActorRef[OrderActor.Command],
      paymentActorRef: ActorRef[PaymentActor.Command],
      inventoryActorRef: ActorRef[InventoryActor.Command],
      deliveryActorRef: ActorRef[DeliveryActor.Command],
      requestTimeout: FiniteDuration
  ): CommandHandler[Command, Event, State] = { (state, command) =>
    (state, command) match {
      // プロセスの開始
      case (EmptyState, Execute(id, itemId, num, replyTo)) =>
        val confirmAdapter: ActorRef[OrderActor.CreateOrderReply] =
          context.messageAdapter[OrderActor.CreateOrderReply](WrappedCreateOrderReply.apply)
        Effect.persist(OrderSaga.CreateOrderSent(id, itemId, num, replyTo)).thenRun { _ =>
          orderActorRef ! OrderActor.CreateOrder(UUID.randomUUID(), itemId, num, Instant.now(), confirmAdapter)
          timers.startSingleTimer(id, CreateOrderRetry(id, 2), requestTimeout)
        }
      // OrderActorから返事が来ない場合
      case (JustState(pendingOrders, _), CreateOrderRetry(requestId, attempt)) =>
        // リトライする
        val confirmAdapter: ActorRef[OrderActor.CreateOrderReply] =
          context.messageAdapter[OrderActor.CreateOrderReply](WrappedCreateOrderReply.apply)
        val orderDetail: OrderDetail = pendingOrders.apply(requestId)
        orderActorRef ! OrderActor.CreateOrder(
          requestId,
          orderDetail.itemId,
          orderDetail.num,
          Instant.now(),
          confirmAdapter
        )
        // TODO: Exponential Backoff
        timers.startSingleTimer(requestId, CreateOrderRetry(requestId, attempt + 1), requestTimeout)
        Effect.none
      // OrderActorが成功した場合
      case (JustState(_, _), WrappedCreateOrderReply(OrderActor.CreateOrderSucceeded(requestId))) =>
        timers.cancel(requestId)
        Effect.persist(OrderSaga.CreateOrderSucceeded(requestId)).thenRun { _ =>
          // TODO: ProcessPaymentをPaymentActorに送信する
        }
      // OrderActorが失敗した場合
      case (JustState(_, _), WrappedCreateOrderReply(OrderActor.CreateOrderFailed(requestId))) =>
        timers.cancel(requestId)
        Effect.persist(OrderSaga.CreateOrderFailed(requestId)).thenRun { case s: JustState =>
          // 失敗をクライアントに返す
          s.replyTo ! ExecuteFailed(requestId)
        }
    }
  }

  private def eventHandler: EventHandler[State, Event] = { (state, event) =>
    (event, state) match {
      case (OrderSaga.CreateOrderSent(requestId, itemId, num, replyTo), EmptyState) =>
        JustState(Map(requestId -> OrderDetail(itemId, num)), replyTo)
      case (OrderSaga.CreateOrderSucceeded(requestId), JustState(pendingOrders, replyTo)) =>
        JustState(pendingOrders - requestId, replyTo)
      case (OrderSaga.CreateOrderFailed(requestId), JustState(pendingOrders, replyTo)) =>
        JustState(pendingOrders - requestId, replyTo)
    }
  }

}
