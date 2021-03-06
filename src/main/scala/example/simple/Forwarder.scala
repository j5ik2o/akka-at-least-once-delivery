package example.simple

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, LoggerOps, TimerScheduler }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.scaladsl.EventSourcedBehavior.{ CommandHandler, EventHandler }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted }
import example.CborSerializable

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

object Forwarder {
  // --- コマンド
  sealed trait Command

  // メッセージを送信するコマンド
  final case class Forward(payload: Receiver.Message) extends Command with CborSerializable

  // Receiverからの返信を示すコマンド
  private final case class WrappedReply(confirm: Receiver.Reply) extends Command

  // メッセージを再送信するコマンド
  private final case class ForwardRetry(deliveryId: Long, attempt: Int) extends Command

  // Receiverが停止したことを通知するコマンド
  private case object ReceiverTerminated extends Command

  // --- イベント
  sealed trait Event extends CborSerializable

  // メッセージを送信したイベント
  final case class MessageSent(payload: Receiver.Message) extends Event

  // 応答メッセージがきたイベント
  final case class MessageReplied(deliveryId: Long) extends Event

  // フォワーダーの状態
  private final case class State(
      lastDeliveryId: Long,
      pendingPayloads: Map[String, Receiver.Message]
  ) extends CborSerializable {

    // ペンディングを追加する
    def add(payload: Receiver.Message): State = {
      val nextDeliveryId = lastDeliveryId + 1
      copy(
        lastDeliveryId = nextDeliveryId,
        pendingPayloads = pendingPayloads + (nextDeliveryId.toString -> payload)
      )
    }

    // ペンディングを削除する
    def remove(deliveryId: Long): State =
      copy(pendingPayloads = pendingPayloads - deliveryId.toString)

  }

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
        } // .withRetention(RetentionCriteria.snapshotEvery(100, 3).withDeleteEventsOnSnapshot)
      }
    }
  }

  private def commandHandler(
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      receiverRef: ActorRef[Receiver.Command],
      forwardTimeout: FiniteDuration
  ): CommandHandler[Command, Event, State] = { (state, command) =>
    val confirmAdapter: ActorRef[Receiver.Reply] =
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

      // Receiverが終了した
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
        state.add(payload)
      // 送信完了イベントのとき
      case MessageReplied(deliveryId) =>
        // ステートからペンディングエントリを削除
        state.remove(deliveryId)
    }
  }

  private def redeliverPendingMessages(
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      state: State,
      receiverRef: ActorRef[Receiver.Command],
      forwardTimeout: FiniteDuration
  ): Unit = {
    val confirmAdapter: ActorRef[Receiver.Reply] =
      context.messageAdapter[Receiver.Reply](WrappedReply.apply)
    context.log.info("--- started: RecoveryCompleted ---")
    // 状態を復元してペンディング中のすべてのメッセージを再送信する。
    state.pendingPayloads.toList
      .sortBy { case (deliveryId, _) => deliveryId }
      .foreach { case (id, payload) =>
        val deliveryId = id.toLong
        context.log.info("receiveSignal: Deliver #{} to {} after recovery", deliveryId, receiverRef)
        receiverRef ! Receiver.Request(deliveryId, payload, confirmAdapter)
        timers.startSingleTimer(deliveryId, ForwardRetry(deliveryId, 2), forwardTimeout)
      }
    context.log.info("--- finished: RecoveryCompleted ---")
  }

}
