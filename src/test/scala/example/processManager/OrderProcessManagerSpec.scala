package example.processManager

import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, SupervisorStrategy }
import com.typesafe.config.{ Config, ConfigFactory }
import example.CborSerializable
import example.processManager.OrderProcessManagerSpec._
import example.processManager.OrderProtocol.CreateOrderReply
import example.processManager.billing.BillingProtocol
import example.processManager.stock.StockProtocol
import org.scalatest.freespec.AnyFreeSpecLike

import java.util.UUID
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

case class OrderProcessManagerRefResult(
    sagaRef: ActorRef[OrderProtocol.CommandRequest],
    stockRef: ActorRef[StockProtocol.CommandRequest],
    stockProbe: TestProbe[StockProtocol.CommandRequest],
    billingRef: ActorRef[BillingProtocol.CommandRequest],
    billingProbe: TestProbe[BillingProtocol.CommandRequest]
)

object OrderProcessManagerSpec {
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
        message.replyTo.tell(StockProtocol.SecureStockSucceeded(UUID.randomUUID(), message.id, message.stockId))
      case _ => ctx.log.error("message: {}", message)
    }
  }

  final val DEFAULT_BILLING_HANDLER: MessageHandler[BillingProtocol.CommandRequest] = { (ctx, message) =>
    message match {
      case message: BillingProtocol.CreateBilling =>
        message.replyTo.tell(BillingProtocol.CreateBillingSucceeded(UUID.randomUUID(), message.id, message.billingId))
      case _ => ctx.log.error("message: {}", message)
    }
  }

  final val MIN_BACKOFF: FiniteDuration = 100.millis
  final val MAX_BACKOFF: FiniteDuration = 1000.millis
  final val RANDOM_FACTOR: Double       = 0.8
  final val BACKOFF_SETTINGS            = BackoffSettings(MIN_BACKOFF, MAX_BACKOFF, RANDOM_FACTOR)
}

class OrderProcessManagerSpec extends ScalaTestWithActorTestKit(OrderProcessManagerSpec.config) with AnyFreeSpecLike {
  "OrderProcessManager" - {
    "OrderSagaに注文することができる" in {
      val orderId                      = OrderId()
      val orderItems                   = newOrderItems()
      val createOrderReplyTestProbe    = testKit.createTestProbe[CreateOrderReply]()
      val orderProcessManagerRefResult = newOrderProcessManagerRef(orderId, BACKOFF_SETTINGS)

      orderProcessManagerRefResult.sagaRef ! OrderProtocol.CreateOrder(
        UUID.randomUUID(),
        orderId,
        orderItems,
        createOrderReplyTestProbe.ref
      )

      val reply = createOrderReplyTestProbe.expectMessageType[OrderProtocol.CreateOrderSucceeded]
      assert(orderId == reply.orderId)

      orderProcessManagerRefResult.stockProbe.expectMessageType[StockProtocol.SecureStock]
      orderProcessManagerRefResult.billingProbe.expectMessageType[BillingProtocol.CreateBilling]
    }
  }

  private def newStockActorRef(
      f: MessageHandler[StockProtocol.CommandRequest] = DEFAULT_STOCK_HANDLER
  ): (ActorRef[StockProtocol.CommandRequest], TestProbe[StockProtocol.CommandRequest]) = {
    val mockStockBehavior = Behaviors.receive[StockProtocol.CommandRequest] { (ctx, message) =>
      f(ctx, message)
      Behaviors.same
    }
    val mockStockBehaviorWithSupervisor = Behaviors.supervise(mockStockBehavior).onFailure(SUPERVISOR_STRATEGY)
    val stockProbe                      = testKit.createTestProbe[StockProtocol.CommandRequest]()
    (
      testKit.spawn(Behaviors.monitor[StockProtocol.CommandRequest](stockProbe.ref, mockStockBehaviorWithSupervisor)),
      stockProbe
    )
  }

  private def newBillingActorRef(
      f: MessageHandler[BillingProtocol.CommandRequest] = DEFAULT_BILLING_HANDLER
  ): (ActorRef[BillingProtocol.CommandRequest], TestProbe[BillingProtocol.CommandRequest]) = {
    val mockBillingBehavior = Behaviors.receive[BillingProtocol.CommandRequest] { (ctx, message) =>
      f(ctx, message)
      Behaviors.same
    }
    val billingProbe = testKit.createTestProbe[BillingProtocol.CommandRequest]()
    val mockBillingBehaviorWithSupervisor = Behaviors
      .supervise(mockBillingBehavior)
      .onFailure(
        SUPERVISOR_STRATEGY
      )
    (
      testKit.spawn(
        Behaviors.monitor[BillingProtocol.CommandRequest](
          billingProbe.ref,
          mockBillingBehaviorWithSupervisor
        )
      ),
      billingProbe
    )
  }

  private def newOrderProcessManagerRef(
      orderId: OrderId,
      backoffSettings: BackoffSettings,
      stockHandler: MessageHandler[StockProtocol.CommandRequest] = DEFAULT_STOCK_HANDLER,
      billingHandler: MessageHandler[BillingProtocol.CommandRequest] = DEFAULT_BILLING_HANDLER
  ): OrderProcessManagerRefResult = {
    val stockRefWithProbe   = newStockActorRef(stockHandler)
    val billingRefWithProbe = newBillingActorRef(billingHandler)

    val sagaActorRef =
      testKit.spawn(
        OrderProcessManager(
          orderId,
          backoffSettings,
          stockRefWithProbe._1,
          billingRefWithProbe._1
        )
      )

    OrderProcessManagerRefResult(
      sagaActorRef,
      stockRefWithProbe._1,
      stockRefWithProbe._2,
      billingRefWithProbe._1,
      billingRefWithProbe._2
    )
  }
}
