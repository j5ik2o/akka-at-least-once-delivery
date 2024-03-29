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

import example.CborSerializable
import example.persistence.domain.Money
import example.support.AggregateEvent

import java.time.Instant

object BankAccountEvents {
  sealed trait Event extends AggregateEvent with CborSerializable {
    override type AggregateIdType = BankAccountAggregateId
  }

  final case class BankAccountCreated(
      override val aggregateId: BankAccountAggregateId,
      override val occurredAt: Instant
  ) extends Event

  final case class CashDeposited(
      override val aggregateId: BankAccountAggregateId,
      amount: Money,
      override val occurredAt: Instant
  ) extends Event

  final case class CashWithdrew(
      override val aggregateId: BankAccountAggregateId,
      amount: Money,
      override val occurredAt: Instant
  ) extends Event
}
