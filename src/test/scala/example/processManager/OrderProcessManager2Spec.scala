package example.processManager

import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, SupervisorStrategy }
import com.typesafe.config.{ Config, ConfigFactory }
import example.CborSerializable
import example.processManager.OrderProcessManager2Spec._
import example.processManager.OrderProtocol.CreateOrderReply
import example.processManager.billing.{ BillingError, BillingId, BillingProtocol }
import example.processManager.stock.StockProtocol
import org.scalatest.freespec.AnyFreeSpecLike

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

private case class OrderProcessManager2RefResult(
    processManagerRef: ActorRef[OrderProtocol.CommandRequest],
    stockRef: ActorRef[StockProtocol.CommandRequest],
    stockProbe: TestProbe[StockProtocol.CommandRequest],
    billingRef: ActorRef[BillingProtocol.CommandRequest],
    billingProbe: TestProbe[BillingProtocol.CommandRequest]
)

object OrderProcessManager2Spec {
  val config: Config = ConfigFactory.parseString(s"""
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.persistence.journal.inmem.test-serialization = on
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/${getClass.getName}-${UUID.randomUUID().toString}"
    akka.actor.serialization-bindings {
      "${classOf[CborSerializable].getName}" = jackson-cbor
    }
    """)

  private def newOrderItems(): OrderItems = {
    val orderItem = newOrderItem()
    OrderItems(orderItem)
  }

  private def newOrderItem(): OrderItem = {
    val orderItemId = OrderItemId()
    val itemId      = ItemId()
    val itemPrice   = ItemPrice(1)
    val quantity    = ItemQuantity(1)
    OrderItem(orderItemId, itemId, itemPrice, quantity)
  }
  val SUPERVISOR_STRATEGY: SupervisorStrategy = SupervisorStrategy.restart

  type MessageHandler[M] = (ActorContext[M], M) => Unit

  final val DEFAULT_STOCK_HANDLER: MessageHandler[StockProtocol.CommandRequest] = { (ctx, message) =>
    message match {
      case message: StockProtocol.SecureStock =>
        message.replyTo ! StockProtocol.SecureStockSucceeded(UUID.randomUUID(), message.id, message.stockId)
      case _ => ctx.log.error("message: {}", message)
    }
  }

  final val DEFAULT_BILLING_HANDLER: MessageHandler[BillingProtocol.CommandRequest] = { (ctx, message) =>
    message match {
      case message: BillingProtocol.CreateBilling =>
        message.replyTo ! BillingProtocol.CreateBillingSucceeded(UUID.randomUUID(), message.id, message.billingId)
      case _ => ctx.log.error("message: {}", message)
    }
  }

  final val MIN_BACKOFF: FiniteDuration = 100.millis
  final val MAX_BACKOFF: FiniteDuration = 1000.millis
  final val RANDOM_FACTOR: Double       = 0.8
  final val BACKOFF_SETTINGS            = BackoffSettings(MIN_BACKOFF, MAX_BACKOFF, RANDOM_FACTOR)
}
