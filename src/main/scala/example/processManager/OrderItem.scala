package example.processManager

import example.CborSerializable

final case class OrderItem(orderItemId: OrderItemId, itemId: ItemId, itemPrice: ItemPrice, itemQuantity: ItemQuantity)
    extends CborSerializable
