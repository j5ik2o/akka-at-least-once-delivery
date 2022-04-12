package example.processManager.billing

import example.CborSerializable
import example.processManager.{ ItemId, ItemPrice }

final case class BillingItem(id: BillingItemId, itemId: ItemId, itemPrice: ItemPrice) extends CborSerializable
