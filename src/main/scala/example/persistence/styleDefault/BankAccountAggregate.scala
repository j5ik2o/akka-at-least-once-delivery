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
package example.persistence.styleDefault

import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }
import example.persistence.domain.BankAccount
import example.persistence.styleDefault.BankAccountAggregate.States.Created
import example.persistence.{ BankAccountAggregateId, BankAccountCommands, BankAccountEvents }

import java.time.Instant

/** このスタイルの問題
  *
  *   - デメリット
  *     - Behaviorを使ったアクタープログラミングができない。状態が複雑な場合は保守性が下がる
  *     - コマンドハンドラでドメインオブジェクトが使いにくい
  *   - メリット
  *     - 記述するコード量が少ない
  */
object BankAccountAggregate {

  object States {
    sealed trait State
    final case object NotCreated                                                            extends State
    final case class Created(aggregateId: BankAccountAggregateId, bankAccount: BankAccount) extends State
  }

  def apply(aggregateId: BankAccountAggregateId): Behavior[BankAccountCommands.Command] = {
    EventSourcedBehavior.withEnforcedReplies(
      persistenceId = PersistenceId.ofUniqueId(aggregateId.asString),
      emptyState = States.NotCreated,
      commandHandler,
      eventHandler
    )
  }

  private def commandHandler
      : (States.State, BankAccountCommands.Command) => ReplyEffect[BankAccountEvents.Event, States.State] = {
    case (Created(_, bankAccount), BankAccountCommands.GetBalance(aggregateId, replyTo)) =>
      Effect.reply(replyTo)(BankAccountCommands.GetBalanceReply(aggregateId, bankAccount.balance))
    case (_, BankAccountCommands.CreateBankAccount(aggregateId, replyTo)) =>
      Effect.persist(BankAccountEvents.BankAccountCreated(aggregateId, Instant.now())).thenReply(replyTo) { _ =>
        BankAccountCommands.CreateBankAccountSucceeded(aggregateId)
      }
    case (state: Created, BankAccountCommands.DepositCash(aggregateId, amount, replyTo)) =>
      // NOTE: コマンドはドメインロジックを呼び出す
      state.bankAccount.add(amount) match {
        // NOTE: コマンドハンドラではステートを更新できないので、戻り値は捨てることになる…
        case Right(_) =>
          Effect.persist(BankAccountEvents.CashDeposited(aggregateId, amount, Instant.now())).thenReply(replyTo) { _ =>
            BankAccountCommands.DepositCashSucceeded(aggregateId)
          }
        case Left(error) =>
          Effect.reply(replyTo)(BankAccountCommands.DepositCashFailed(aggregateId, error))
      }
  }

  private def eventHandler: (States.State, BankAccountEvents.Event) => States.State = {
    case (_, BankAccountEvents.BankAccountCreated(aggregateId, _)) =>
      Created(aggregateId, bankAccount = BankAccount(aggregateId.toEntityId))
    case (Created(_, bankAccount), BankAccountEvents.CashDeposited(aggregateId, amount, _)) =>
      bankAccount.add(amount) match {
        case Right(result) => Created(aggregateId, bankAccount = result)
        case Left(error)   => throw new Exception(s"error = $error")
      }
  }

}
