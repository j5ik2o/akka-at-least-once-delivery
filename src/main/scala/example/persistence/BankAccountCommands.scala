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
package example.persistence

import akka.actor.typed.ActorRef
import example.persistence.domain.{ BankAccountError, Money }
import example.persistence.styleEffector.BankAccountAggregate
import example.support.{ AggregateCommand, StateRecoveryCompleted }

object BankAccountCommands {
  sealed trait Command extends AggregateCommand {
    override type AggregateIdType = BankAccountAggregateId
  }

  final case class CreateBankAccount(
      override val aggregateId: BankAccountAggregateId,
      replyTo: ActorRef[CreateBankAccountReply]
  ) extends Command
  sealed trait CreateBankAccountReply
  final case class CreateBankAccountSucceeded(aggregateId: BankAccountAggregateId) extends CreateBankAccountReply

  final case class DepositCash(
      override val aggregateId: BankAccountAggregateId,
      amount: Money,
      replyTo: ActorRef[DepositCashReply]
  ) extends Command
  sealed trait DepositCashReply
  final case class DepositCashSucceeded(aggregateId: BankAccountAggregateId) extends DepositCashReply
  final case class DepositCashFailed(aggregateId: BankAccountAggregateId, error: BankAccountError)
      extends DepositCashReply
  final case class GetBalance(
      override val aggregateId: BankAccountAggregateId,
      replyTo: ActorRef[GetBalanceReply]
  ) extends Command

  final case class GetBalanceReply(aggregateId: BankAccountAggregateId, balance: Money)

  private[persistence] final case class WrappedStateRecoveryCompleted(
      override val aggregateId: BankAccountAggregateId,
      inner: StateRecoveryCompleted[BankAccountAggregate.States.State]
  ) extends Command

  private[persistence] final case class WrappedPersisted(
      override val aggregateId: BankAccountAggregateId,
      newState: BankAccountAggregate.States.State,
      event: BankAccountEvents.Event
  ) extends Command
}
