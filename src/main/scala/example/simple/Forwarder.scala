package example.simple
// https://gist.github.com/patriknw/514bae62134050f24ca7af95ee977e54
import akka.actor.typed.scaladsl.{ Behaviors, LoggerOps }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, RetentionCriteria }
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted }
import example.CborSerializable

import scala.concurrent.duration.FiniteDuration

object Forwarder {
  // --- Commands
  sealed trait Command

  final case class Forward(payload: Destination.Payload) extends Command with CborSerializable

  private final case class WrappedReply(confirm: Destination.Reply) extends Command

  private final case class ForwardRetry(deliveryId: Long, attempt: Int) extends Command

  private case object DestinationTerminated extends Command

  // --- Events
  sealed trait Event extends CborSerializable

  final case class MessageSent(payload: Destination.Payload) extends Event

  final case class MessageReplied(deliveryId: Long) extends Event

  // key in the `pending` Map is the deliveryId
  // using Map[String, _] due to serialization problem of Map[Long, _]
  final case class State(
      lastDeliveryId: Long,
      pendingPayloads: Map[String, Destination.Payload]
  ) extends CborSerializable

  def apply(
      persistenceId: PersistenceId,
      destinationRef: ActorRef[Destination.Command],
      forwardTimeout: FiniteDuration
  ): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        // 宛先が停止したときにメッセージを受ける
        context.watchWith(destinationRef, DestinationTerminated)

        val confirmAdapter =
          context.messageAdapter[Destination.Reply](WrappedReply.apply)

        EventSourcedBehavior[Command, Event, State](
          persistenceId,
          emptyState = State(0L, Map.empty),
          commandHandler = (state, command) =>
            command match {
              // 宛先にメッセージを送信するとき
              case Forward(payload) =>
                // 送信開始イベントを永続化する
                Effect.persist(MessageSent(payload)).thenRun { newState =>
                  val deliveryId = newState.lastDeliveryId
                  context.log.info("Deliver #{} to {}", deliveryId, destinationRef)
                  // 宛先のアクターへメッセージを送信する
                  destinationRef ! Destination.Request(deliveryId, payload, confirmAdapter)
                  // タイマーを仕掛ける。タイムアウト時はRedeliverを送信する
                  timers.startSingleTimer(deliveryId, ForwardRetry(deliveryId, 2), forwardTimeout)
                }
              // タイムアウトせずに返信がきたとき
              case WrappedReply(Destination.Reply(deliveryId)) =>
                context.log.info("Confirmed #{} from {}", deliveryId, destinationRef)
                // タイマーを停止する
                timers.cancel(deliveryId)
                // 送信完了イベントを永続化
                Effect.persist(MessageReplied(deliveryId))
              // タイムアウトして返信がないとき
              case ForwardRetry(deliveryId, attempt) =>
                context.log.infoN("Redeliver #{}, attempt {}, to {}", deliveryId, attempt, destinationRef)
                // 状態からペイロードを取得
                val payload = state.pendingPayloads(deliveryId.toString)
                // 再送する
                destinationRef ! Destination.Request(deliveryId, payload, confirmAdapter)
                // タイマーを仕掛ける。試行回数を追加する
                timers.startSingleTimer(deliveryId, ForwardRetry(deliveryId, attempt + 1), forwardTimeout)
                Effect.none
              case DestinationTerminated =>
                context.log.warn("Destination {} terminated", destinationRef)
                Effect.stop()
            },
          eventHandler = (state, event) =>
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
        ).receiveSignal { case (state, RecoveryCompleted) =>
          context.log.info("--- started: RecoveryCompleted ---")
          // 状態を復元してペンディング中のすべてのメッセージを再送信する。
          state.pendingPayloads.toList
            .sortBy { case (deliveryId, _) => deliveryId }
            .foreach { case (id, payload) =>
              val deliveryId = id.toLong // workaround for CborSerializaton issue of Map[Long, _]
              context.log.info("receiveSignal: Deliver #{} to {} after recovery", deliveryId, destinationRef)
              destinationRef ! Destination.Request(deliveryId, payload, confirmAdapter)
              timers.startSingleTimer(deliveryId, ForwardRetry(deliveryId, 2), forwardTimeout)
            }
          context.log.info("--- finished: RecoveryCompleted ---")
        }.withRetention(RetentionCriteria.snapshotEvery(100, 3).withDeleteEventsOnSnapshot)
      }
    }
  }

}
