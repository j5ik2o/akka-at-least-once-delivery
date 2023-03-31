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
package example.persistence.styleEffector

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer }
import example.persistence.{ BankAccountAggregateId, BankAccountCommands, BankAccountEvents }
import example.persistence.domain.{ BankAccount, BankAccountId, Money }
import example.persistence.styleEffector.BankAccountAggregate.States.Created
import example.persistence.styleEffector.BankAccountAggregate.{ actorName, CTX }
import example.support.{ AggregateId, AggregateTypeName, PersistEffect, PersistentMode }

import java.time.Instant
import java.util.{ Currency, UUID }

/** このスタイルのPros/Cons
  *
  *   - Pros
  *     - コマンドハンドラにドメインロジックが記述しやすい
  *     - Behaviorを使ったプログラミングができる
  *   - Cons
  *     - 記述量が増える
  */
object BankAccountAggregate {
  type CTX = ActorContext[BankAccountCommands.Command]

  object States {
    sealed trait State extends example.support.State {
      override type This  = BankAccountAggregate.States.State
      override type Event = BankAccountEvents.Event
    }

    final case object NotCreated extends State {
      override def applyEvent(event: BankAccountEvents.Event): State = event match {
        case BankAccountEvents.BankAccountCreated(aggregateId, _) =>
          Created(aggregateId, bankAccount = new BankAccount(aggregateId.toEntityId))
        case _ => throw new IllegalStateException(s"Unexpected event: $event")
      }
    }

    final case class Created(aggregateId: BankAccountAggregateId, bankAccount: BankAccount) extends State {
      override def applyEvent(event: BankAccountEvents.Event): State = event match {
        case BankAccountEvents.CashDeposited(aggregateId, amount, _) =>
          // NOTE: ここは同じような書き方になる
          bankAccount.add(amount) match {
            case Right(result) => Created(aggregateId, bankAccount = result)
            case Left(error)   => throw new Exception(s"error = $error")
          }
      }
    }
  }

  // アクター名を生成するためのヘルパー関数
  def actorName(aggregateId: BankAccountAggregateId): String =
    s"${aggregateId.aggregateTypeName}-${aggregateId.asString}"

  // NOTE: PersistentMode.InMemoryの場合は、akka-persistenceのための設定・初期化などが不要です。
  // 通常のインメモリなアクターとしてテストできます。
  def apply(
      aggregateId: BankAccountAggregateId,
      persistentMode: PersistentMode,
      stashBufferSize: Int = Int.MaxValue
  ): Behavior[BankAccountCommands.Command] =
    Behaviors.setup { implicit ctx =>
      Behaviors.withStash(stashBufferSize) { implicit stashBuffer =>
        new BankAccountAggregate(aggregateId, persistentMode).replayHandler
      }
    }

}

class BankAccountAggregate(aggregateId: BankAccountAggregateId, persistentMode: PersistentMode)(implicit
    ctx: CTX,
    stashBuffer: StashBuffer[BankAccountCommands.Command]
) extends PersistEffect {
  override type S = BankAccountAggregate.States.State
  override type E = BankAccountEvents.Event

  private val effector = createEffector[BankAccountCommands.Command](
    persistenceId = actorName(aggregateId),
    persistentMode,
    emptyStateF = _ => BankAccountAggregate.States.NotCreated,
    stateRecoveryCompletedMapper = msg => BankAccountCommands.WrappedStateRecoveryCompleted(aggregateId, msg),
    replyMapper = { case Persisted(newState, event) =>
      BankAccountCommands.WrappedPersisted(aggregateId, newState, event)
    },
    stateWithEventExtractor = {
      case BankAccountCommands.WrappedPersisted(_, newState, event) => Some((newState, event))
      case _                                                        => None
    }
  )

  // リプレイハンドラ
  private def replayHandler: Behavior[BankAccountCommands.Command] = Behaviors.receiveMessagePartial {
    // リカバリー完了時の処理
    case BankAccountCommands.WrappedStateRecoveryCompleted(_, example.support.StateRecoveryCompleted(state)) =>
      ctx.log.info("State recovery completed: {}", state)
      state match {
        // 新規口座の場合は、未開設状態から始まる
        case BankAccountAggregate.States.NotCreated =>
          stashBuffer.unstashAll(notCreated)
        // 既存口座は、開設済み状態から始まる
        case state: BankAccountAggregate.States.Created =>
          stashBuffer.unstashAll(created(state))
      }
    case msg =>
      // リプレイ中のコマンドはスタッシュしておく
      stashBuffer.stash(msg)
      Behaviors.same
  }

  // 未開設状態のコマンドハンドラ
  private def notCreated: Behavior[BankAccountCommands.Command] =
    Behaviors.receiveMessagePartial {
      // 口座の開設
      case BankAccountCommands.CreateBankAccount(aggregateId: AggregateId, replyTo) =>
        effector.persist(BankAccountEvents.BankAccountCreated(aggregateId, Instant.now())) { case (state: Created, _) =>
          replyTo ! BankAccountCommands.CreateBankAccountSucceeded(aggregateId)
          created(state)
        }
    }

  // 開設済み状態のコマンドハンドラ
  private def created(state: Created): Behavior[BankAccountCommands.Command] =
    Behaviors.receiveMessagePartial {
      // 残高の取得
      case BankAccountCommands.GetBalance(aggregateId, replyTo) =>
        replyTo ! BankAccountCommands.GetBalanceReply(aggregateId, state.bankAccount.balance)
        Behaviors.same
      // 現金の入金
      case BankAccountCommands.DepositCash(aggregateId, amount, replyTo) =>
        state.bankAccount.add(amount) match {
          // NOTE: 戻り値は捨てずに次のステートに組み込むことができる
          case Right(result) =>
            val newState = state.copy(bankAccount = result)
            effector.persist(BankAccountEvents.CashDeposited(aggregateId, amount, Instant.now())) { case (_, _) =>
              replyTo ! BankAccountCommands.DepositCashSucceeded(aggregateId)
              created(newState)
            }
          case Left(error) =>
            replyTo ! BankAccountCommands.DepositCashFailed(aggregateId, error)
            Behaviors.same
        }
    }
}
