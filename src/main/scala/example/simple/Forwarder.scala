package example.simple

// https://gist.github.com/patriknw/514bae62134050f24ca7af95ee977e54

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, LoggerOps, TimerScheduler }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.scaladsl.EventSourcedBehavior.{ CommandHandler, EventHandler }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, RetentionCriteria }
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted }
import example.CborSerializable

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

object Forwarder {
  // --- Commands
  sealed trait Command

  final case class Forward(payload: Receiver.Payload) extends Command with CborSerializable

  private final case class WrappedReply(confirm: Receiver.Reply) extends Command

  private final case class ForwardRetry(deliveryId: Long, attempt: Int) extends Command

  private case object ReceiverTerminated extends Command

  // --- Events
  sealed trait Event extends CborSerializable

  // メッセージを送信したイベント
  final case class MessageSent(payload: Receiver.Payload) extends Event

  // 応答メッセージがきたイベント
  final case class MessageReplied(deliveryId: Long) extends Event

  private final case class State(
      lastDeliveryId: Long,
      pendingPayloads: Map[String, Receiver.Payload]
  ) extends CborSerializable

  // ビヘイビアを返すファクトリ
  def apply(
      id: UUID,
      receiverRef: ActorRef[Receiver.Command],
      forwardTimeout: FiniteDuration
  ): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        // 宛先が停止したときにメッセージを受ける
        context.watchWith(receiverRef, ReceiverTerminated)
        // 永続化アクターのビヘイビアを定義
        EventSourcedBehavior[Command, Event, State](
          PersistenceId.ofUniqueId(id.toString),                        // ID
          emptyState = State(0L, Map.empty),                            // 初期状態
          commandHandler(context, timers, receiverRef, forwardTimeout), // コマンドハンドラ
          eventHandler                                                  // イベントハンドラ
        ).receiveSignal { case (state, RecoveryCompleted) =>
          // アクターのリプレイが完了したとき、ペンディングが残っていたら再送を行う
          redeliverPendingMessages(context, timers, state, receiverRef, forwardTimeout)
        }.withRetention(RetentionCriteria.snapshotEvery(100, 3).withDeleteEventsOnSnapshot)
      }
    }
  }

  private def commandHandler(
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      receiverRef: ActorRef[Receiver.Command],
      forwardTimeout: FiniteDuration
  ): CommandHandler[Command, Event, State] = { (state, command) =>
    def confirmAdapter: ActorRef[Receiver.Reply] =
      context.messageAdapter[Receiver.Reply](WrappedReply.apply)
    command match {
      // 宛先にメッセージを送信するとき
      case Forward(payload) =>
        // 送信開始イベントを永続化する
        Effect.persist(MessageSent(payload)).thenRun { newState =>
          val deliveryId = newState.lastDeliveryId
          context.log.info("Deliver #{} to {}", deliveryId, receiverRef)
          // 宛先のアクターへメッセージを送信する(tell)
          receiverRef ! Receiver.Request(deliveryId, payload, confirmAdapter)
          // タイマーを仕掛ける。タイムアウト時はRedeliverを送信する
          timers.startSingleTimer(deliveryId, ForwardRetry(deliveryId, 2), forwardTimeout)
        }
      // タイムアウトせずに返信がきたとき(正常系)
      case WrappedReply(Receiver.Reply(deliveryId)) =>
        context.log.info("Confirmed #{} from {}", deliveryId, receiverRef)
        // タイマーを停止する
        timers.cancel(deliveryId)
        // 送信完了イベントを永続化
        Effect.persist(MessageReplied(deliveryId))
      // タイムアウトして返信がないとき(異常系)
      case ForwardRetry(deliveryId, attempt) =>
        context.log.infoN("Redeliver #{}, attempt {}, to {}", deliveryId, attempt, receiverRef)
        // 状態からペイロードを取得
        val payload = state.pendingPayloads(deliveryId.toString)
        // 再送する
        receiverRef ! Receiver.Request(deliveryId, payload, confirmAdapter)
        // タイマーを仕掛ける。試行回数を追加する
        timers.startSingleTimer(deliveryId, ForwardRetry(deliveryId, attempt + 1), forwardTimeout)
        Effect.none
      case ReceiverTerminated =>
        context.log.warn("Receiver {} terminated", receiverRef)
        Effect.stop()
    }
  }

  private def eventHandler: EventHandler[State, Event] = { (state, event) =>
    event match {
      // 送信開始イベントのとき
      case MessageSent(payload) =>
        // ステートにペンディングエントリを追加
        val nextDeliveryId = state.lastDeliveryId + 1
        state.copy(
          lastDeliveryId = nextDeliveryId,
          pendingPayloads = state.pendingPayloads + (nextDeliveryId.toString -> payload)
        )
      // 送信完了イベントのとき
      case MessageReplied(deliveryId) =>
        // ステートからペンディングエントリを削除
        state.copy(pendingPayloads = state.pendingPayloads - deliveryId.toString)
    }
  }

  private def redeliverPendingMessages(
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      state: State,
      receiverRef: ActorRef[Receiver.Command],
      forwardTimeout: FiniteDuration
  ): Unit = {
    def confirmAdapter: ActorRef[Receiver.Reply] =
      context.messageAdapter[Receiver.Reply](WrappedReply.apply)
    context.log.info("--- started: RecoveryCompleted ---")
    // 状態を復元してペンディング中のすべてのメッセージを再送信する。
    state.pendingPayloads.toList
      .sortBy { case (deliveryId, _) => deliveryId }
      .foreach { case (id, payload) =>
        val deliveryId = id.toLong // workaround for CborSerializaton issue of Map[Long, _]
        context.log.info("receiveSignal: Deliver #{} to {} after recovery", deliveryId, receiverRef)
        receiverRef ! Receiver.Request(deliveryId, payload, confirmAdapter)
        timers.startSingleTimer(deliveryId, ForwardRetry(deliveryId, 2), forwardTimeout)
      }
    context.log.info("--- finished: RecoveryCompleted ---")
  }

}
