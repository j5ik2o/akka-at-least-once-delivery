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
package example.persistence.styleInMemory
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import example.persistence.domain.BankAccount
import example.persistence.{ BankAccountAggregateId, BankAccountCommands }

object BankAccountAggregate {
  private final case class Created(aggregateId: BankAccountAggregateId, bankAccount: BankAccount)

  def apply(aggregateId: BankAccountAggregateId): Behavior[BankAccountCommands.Command] = {
    notCreated(aggregateId)
  }

  private def notCreated(
      aggregateId: BankAccountAggregateId
  ): Behavior[BankAccountCommands.Command] =
    Behaviors.receiveMessagePartial {
      // 口座の開設
      case command: BankAccountCommands.CreateBankAccount if command.aggregateId == aggregateId =>
        command.replyTo ! BankAccountCommands.CreateBankAccountSucceeded(command.aggregateId)
        created(Created(aggregateId, bankAccount = BankAccount(command.aggregateId.toEntityId)))
    }

  private def created(state: Created): Behavior[BankAccountCommands.Command] =
    Behaviors.receiveMessagePartial {
      // 残高の取得
      case BankAccountCommands.GetBalance(aggregateId, replyTo) if aggregateId == state.aggregateId =>
        replyTo ! BankAccountCommands.GetBalanceReply(aggregateId, state.bankAccount.balance)
        Behaviors.same
      // 現金の入金
      case BankAccountCommands.DepositCash(aggregateId, amount, replyTo) if aggregateId == state.aggregateId =>
        state.bankAccount.add(amount) match {
          case Right(result) =>
            replyTo ! BankAccountCommands.DepositCashSucceeded(aggregateId)
            created(state.copy(bankAccount = result))
          case Left(error) =>
            replyTo ! BankAccountCommands.DepositCashFailed(aggregateId, error)
            Behaviors.same
        }
      // 現金の出金
      case BankAccountCommands.WithdrawCash(aggregateId, amount, replyTo) if aggregateId == state.aggregateId =>
        state.bankAccount.subtract(amount) match {
          case Right(result) =>
            replyTo ! BankAccountCommands.WithdrawCashSucceeded(aggregateId)
            created(state.copy(bankAccount = result))
          case Left(error) =>
            replyTo ! BankAccountCommands.WithdrawCashFailed(aggregateId, error)
            Behaviors.same
        }

    }
}
