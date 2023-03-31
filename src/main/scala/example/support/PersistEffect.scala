/*
 * Copyright 2023 Junichi Kato
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package example.support

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.persistence.typed.{ PersistenceId, RecoveryCompleted }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/** 集約が生成するイベントを表すトレイト。
  */
trait AggregateEvent {
  type AggregateIdType <: AggregateId
  def aggregateId: AggregateIdType
  def occurredAt: Instant
}

/** アクターの状態を表すトレイト。
  */
trait State {
  type This <: State
  type Event <: AggregateEvent

  /** イベントを適用します。
    *
    * @param event
    *   イベント
    * @return
    *   適用後の状態
    */
  def applyEvent(event: Event): This
}

/** 集約のリプレイが完了したことを表すメッセージ。
  *
  * @param state
  *   リプレイ後の状態
  * @tparam S
  *   状態の型
  */
final case class StateRecoveryCompleted[S <: State](state: S)

object PersistEffect {
  final val actorName: String = "persist"
}

/** アクターの集約をサポートするトレイト。
  *
  * @tparam E
  *   イベントの型
  * @tparam S
  *   状態の型
  */
trait PersistEffect {

  type E <: AggregateEvent

  type S <: State {
    type This <: S
    type Event = E
  }

  private case class Persist(event: E, replyTo: ActorRef[Persisted])
  case class Persisted(newState: S, event: E)

  private val persistStates: scala.collection.mutable.Map[String, Option[S]] =
    new ConcurrentHashMap[String, Option[S]]().asScala

  private def createPersistRef[CMD](
      context: ActorContext[CMD],
      persistenceId: String,
      persistentMode: PersistentMode,
      emptyStateF: String => S,
      stateRecoveryCompletedMapper: StateRecoveryCompleted[S] => CMD
  ): ActorRef[Persist] = {
    val stateRecoveryCompletedAdaptor =
      context.messageAdapter[StateRecoveryCompleted[S]](stateRecoveryCompletedMapper)
    val behavior = behaviorOfPersistEffect(
      persistenceId,
      persistentMode,
      emptyStateF,
      stateRecoveryCompletedAdaptor
    )
    context.spawn(behavior, PersistEffect.actorName)
  }

  private def behaviorOfPersistEffect(
      persistenceId: String,
      persistentMode: PersistentMode,
      emptyStateF: String => S,
      parentRef: ActorRef[StateRecoveryCompleted[S]]
  ): Behavior[Persist] =
    persistentMode match {
      case PersistentMode.InMemory =>
        behaviorOfInMemory(persistenceId, emptyStateF, parentRef)
      case PersistentMode.Persistent =>
        behaviorOfJournal(persistenceId, emptyStateF, parentRef)
    }

  private def behaviorOfInMemory(
      persistenceId: String,
      emptyStateF: String => S,
      parentRef: ActorRef[StateRecoveryCompleted[S]]
  ): Behavior[Persist] = {
    Behaviors.setup { _ =>
      val initialState = persistStates.getOrElseUpdate(persistenceId, None) match {
        case Some(state) =>
          parentRef ! StateRecoveryCompleted(state)
          state
        case None =>
          val state = emptyStateF(persistenceId)
          parentRef ! StateRecoveryCompleted(state)
          state
      }

      def state(s: S): Behavior[Persist] = {
        Behaviors
          .receiveMessage { case Persist(event, replyTo) =>
            val newState = s.applyEvent(event)
            replyTo ! Persisted(newState, event)
            // ctx.log.debug(">>> pid = {}, Persisted: state = {}", persistenceId, newState)
            persistStates.put(persistenceId, Some(newState))
            // ctx.log.debug(">>> pid = {}, persistStates = {}", persistenceId, persistStates)
            state(newState)
          }
      }

      state(initialState)
    }
  }

  private def behaviorOfJournal(
      persistenceId: String,
      emptyStateF: String => S,
      parentRef: ActorRef[StateRecoveryCompleted[S]]
  ): Behavior[Persist] = {
    Behaviors.setup { ctx =>
      EventSourcedBehavior(
        PersistenceId.ofUniqueId(persistenceId),
        emptyState = emptyStateF(persistenceId),
        commandHandler = { (state: S, command: Persist) =>
          (state, command) match {
            case (_, Persist(event, replyTo)) =>
              Effect.persist(event).thenReply(replyTo) { newState: S =>
                Persisted(newState, event)
              }
          }
        },
        eventHandler = { (state: S, event: E) =>
          state.applyEvent(event)
        }
      ).receiveSignal { case (state, RecoveryCompleted) =>
        ctx.log.debug("RecoveryCompleted: state = {}", state)
        parentRef ! StateRecoveryCompleted(state)
      }
    }
  }

  /** 永続化アクターにイベントを送信するためのクラス。
    *
    * @param persistenceId
    *   永続化アクターのID
    * @param persistentMode
    *   永続化モード
    * @param emptyStateF
    *   空の状態を生成する関数
    * @param stateRecoveryCompletedMapper
    *   リプレイ完了メッセージをPersistedに変換する関数
    * @param replyMapper
    *   レスポンスメッセージをPersistedに変換する関数
    * @param stateWithEventExtractor
    *   レスポンスメッセージからイベントを抽出する関数
    * @tparam M
    *   レスポンスメッセージの型
    */
  final class Effector[M](
      persistenceId: String,
      persistentMode: PersistentMode,
      emptyStateF: String => S,
      stateRecoveryCompletedMapper: StateRecoveryCompleted[S] => M,
      replyMapper: Persisted => M,
      stateWithEventExtractor: M => Option[(S, E)],
      stashBufferSize: Int = Int.MaxValue
  )(implicit ctx: ActorContext[M]) {

    private val persistRef: ActorRef[Persist] =
      createPersistRef[M](ctx, persistenceId, persistentMode, emptyStateF, stateRecoveryCompletedMapper)

    /** イベントを永続化します。
      *
      * @param event
      *   イベント
      * @param onSuccess
      *   永続化成功時の処理
      * @param ctx
      *   ActorContext
      * @param persistRef
      *   永続化用ActorRef
      * @return
      */
    def persist(event: E)(
        onSuccess: (S, E) => Behavior[M]
    ): Behavior[M] = {
      val messageAdaptor = ctx.messageAdapter[Persisted](replyMapper)
      persistRef ! Persist(event, messageAdaptor)
      Behaviors.withStash(stashBufferSize) { stashBuffer =>
        Behaviors.receiveMessagePartial { case msg =>
          stateWithEventExtractor(msg) match {
            // 永続化完了メッセージがきたら
            case Some((newState, event)) =>
              // スタッシュしていたメッセージ解放して次の状態へ
              stashBuffer.unstashAll(onSuccess(newState, event))
            // 永続化完了以外のメッセージは一旦スタッシュする
            case None =>
              stashBuffer.stash(msg)
              Behaviors.same
          }
        }
      }
    }
  }

  /** [[Effector]]を作成します。
    *
    * @param persistenceId
    *   永続化アクターのID
    * @param persistentMode
    *   永続化モード
    * @param emptyStateF
    *   空の状態を生成する関数
    * @param stateRecoveryCompletedMapper
    *   リプレイ完了メッセージをPersistedに変換する関数
    * @param replyMapper
    *   レスポンスメッセージをPersistedに変換する関数
    * @param stateWithEventExtractor
    *   レスポンスメッセージからイベントを抽出する関数
    * @tparam M
    *   レスポンスメッセージの型
    * @return
    *   [[Effector]]
    */
  def createEffector[M](
      persistenceId: String,
      persistentMode: PersistentMode,
      emptyStateF: String => S,
      stateRecoveryCompletedMapper: StateRecoveryCompleted[S] => M,
      replyMapper: Persisted => M,
      stateWithEventExtractor: M => Option[(S, E)],
      stashSize: Int = 32
  )(implicit ctx: ActorContext[M]): Effector[M] =
    new Effector(
      persistenceId,
      persistentMode,
      emptyStateF,
      stateRecoveryCompletedMapper,
      replyMapper,
      stateWithEventExtractor,
      stashSize
    )

}

/** アクターの永続化モードを表すトレイト。
  */
sealed trait PersistentMode
object PersistentMode {

  /** インメモリー
    */
  case object InMemory extends PersistentMode

  /** ジャーナル
    */
  case object Persistent extends PersistentMode
}
