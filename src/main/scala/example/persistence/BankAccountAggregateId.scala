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

import example.persistence.domain.BankAccountId
import example.support.{ AggregateId, AggregateTypeName }

import java.util.UUID

final case class BankAccountAggregateId(value: UUID) extends AggregateId {
  override type EntityIdType = BankAccountId

  override def toEntityId: EntityIdType = BankAccountId(value)

  override def asString: String = value.toString

  override def aggregateTypeName: AggregateTypeName = AggregateTypeName("bank-account")
}
