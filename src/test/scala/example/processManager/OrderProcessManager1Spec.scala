package example.processManager

import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, SupervisorStrategy }
import com.typesafe.config.{ Config, ConfigFactory }
import example.CborSerializable
import example.processManager.OrderProcessManager1Spec._
import example.processManager.OrderProtocol.CreateOrderReply
import example.processManager.billing.{ BillingError, BillingId, BillingProtocol }
import example.processManager.stock.StockProtocol
import org.scalatest.freespec.AnyFreeSpecLike

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{ DurationInt, FiniteDuration }

private case class OrderProcessManager1RefResult(
    processManagerRef: ActorRef[OrderProtocol.CommandRequest],
    stockRef: ActorRef[StockProtocol.CommandRequest],
    stockProbe: TestProbe[StockProtocol.CommandRequest],
    billingRef: ActorRef[BillingProtocol.CommandRequest],
    billingProbe: TestProbe[BillingProtocol.CommandRequest]
)

object OrderProcessManager1Spec {
  val config: Config = ConfigFactory.parseString(s"""
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.persistence.journal.inmem.test-serialization = on
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/${getClass.getName}-${UUID.randomUUID().toString}"
    akka.actor.serialization-bindings {
      "${classOf[CborSerializable].getName}" = jackson-cbor
    }
    """)

  def newOrderItems(): OrderItems = {
    val orderItem = newOrderItem()
    OrderItems(orderItem)
  }

  def newOrderItem(): OrderItem = {
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
  final val BACKOFF_SETTINGS: BackoffSettings            = BackoffSettings(MIN_BACKOFF, MAX_BACKOFF, RANDOM_FACTOR)
}

class OrderProcessManagerSpec extends ScalaTestWithActorTestKit(OrderProcessManager1Spec.config) with AnyFreeSpecLike {
  "OrderProcessManager" - {
    "注文することができる" in {
      val orderId                      = OrderId()
      val orderItems                   = newOrderItems()
      val createOrderReplyTestProbe    = testKit.createTestProbe[CreateOrderReply]()
      val orderProcessManagerRefResult = newOrderProcessManager1Ref(orderId, BACKOFF_SETTINGS)

      orderProcessManagerRefResult.processManagerRef ! OrderProtocol.CreateOrder(
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

    "StockActorRefが故障しても再送することで注文することができる" in {
      val orderId                   = OrderId()
      val orderItems                = newOrderItems()
      val createOrderReplyTestProbe = testKit.createTestProbe[OrderProtocol.CreateOrderReply]()

      val counter = new AtomicInteger(0)
      val orderProcessManagerRefResult =
        newOrderProcessManager1Ref(
          orderId,
          BACKOFF_SETTINGS,
          stockHandler = { (ctx, message) =>
            message match {
              case message: StockProtocol.SecureStock =>
                if (counter.incrementAndGet() <= 3) {
                  ctx.log.info(s"counter = $counter")
                  throw new Exception(s"異常が発生しました: counter = $counter")
                } else {
                  message.replyTo ! StockProtocol.SecureStockSucceeded(UUID.randomUUID(), message.id, message.stockId)
                }
              case _ => ctx.log.error(s"message: $message")
            }
          }
        )

      orderProcessManagerRefResult.processManagerRef ! OrderProtocol.CreateOrder(
        UUID.randomUUID(),
        orderId,
        orderItems,
        createOrderReplyTestProbe.ref
      )

      val reply = createOrderReplyTestProbe.expectMessageType[OrderProtocol.CreateOrderSucceeded](
        30.seconds
      )
      assert(orderId == reply.orderId)

      orderProcessManagerRefResult.stockProbe.expectMessageType[StockProtocol.SecureStock]
      orderProcessManagerRefResult.billingProbe.expectMessageType[BillingProtocol.CreateBilling]
    }

    "BillingActorRefが故障してもリトライすることで注文することができる" in {
      val orderId                   = OrderId()
      val orderItems                = newOrderItems()
      val createOrderReplyTestProbe = testKit.createTestProbe[OrderProtocol.CreateOrderReply]()

      val counter = new AtomicInteger(0)
      val orderProcessManagerRefResult = newOrderProcessManager1Ref(
        orderId,
        BACKOFF_SETTINGS,
        billingHandler = { (ctx, message) =>
          message match {
            case message: BillingProtocol.CreateBilling =>
              if (counter.incrementAndGet() <= 3) {
                ctx.log.info(s"counter = $counter")
                throw new Exception(s"異常が発生しました: counter = $counter")
              } else {
                message.replyTo ! BillingProtocol.CreateBillingSucceeded(
                  UUID.randomUUID(),
                  message.id,
                  message.billingId
                )
              }
            case _ => ctx.log.error(s"message: $message")
          }
        }
      )

      orderProcessManagerRefResult.processManagerRef !
      OrderProtocol.CreateOrder(
        UUID.randomUUID(),
        orderId,
        orderItems,
        createOrderReplyTestProbe.ref
      )

      val reply = createOrderReplyTestProbe.expectMessageType[OrderProtocol.CreateOrderSucceeded](
        30.seconds
      )
      assert(orderId == reply.orderId)

      orderProcessManagerRefResult.stockProbe.expectMessageType[StockProtocol.SecureStock]
      orderProcessManagerRefResult.billingProbe.expectMessageType[BillingProtocol.CreateBilling]
    }

    "BillingActorRefがCreateBillingFailedを返したら" - {
      "BillingActorRefへCancelStockを送りクライアントにCreateOrderFailedを返す" in {
        val orderId                   = OrderId()
        val orderItems                = newOrderItems()
        val createOrderReplyTestProbe = testKit.createTestProbe[OrderProtocol.CreateOrderReply]()

        val orderProcessManagerRefResult = newOrderProcessManager1Ref(
          orderId,
          BACKOFF_SETTINGS,
          stockHandler = { (ctx, message) =>
            message match {
              case message: StockProtocol.SecureStock =>
                message.replyTo ! StockProtocol.SecureStockSucceeded(UUID.randomUUID(), message.id, message.stockId)
              case message: StockProtocol.CancelStock =>
                message.replyTo ! StockProtocol.CancelStockSucceeded(UUID.randomUUID(), message.id, message.stockId)
              case _ => ctx.log.error("message: {}", message)
            }
          },
          billingHandler = { (ctx, message) =>
            message match {
              case message: BillingProtocol.CreateBilling =>
                message.replyTo ! BillingProtocol.CreateBillingFailed(
                  UUID.randomUUID(),
                  message.id,
                  message.billingId,
                  BillingError.CreditError(BillingId())
                )
              case _ => ctx.log.error(s"message: $message")
            }
          }
        )

        orderProcessManagerRefResult.processManagerRef ! OrderProtocol.CreateOrder(
          UUID.randomUUID(),
          orderId,
          orderItems,
          createOrderReplyTestProbe.ref
        )

        val reply = createOrderReplyTestProbe.expectMessageType[OrderProtocol.CreateOrderFailed](
          30.seconds
        )
        assert(orderId == reply.orderId)

        orderProcessManagerRefResult.stockProbe.expectMessageType[StockProtocol.SecureStock]
        orderProcessManagerRefResult.billingProbe.expectMessageType[BillingProtocol.CreateBilling]
        orderProcessManagerRefResult.stockProbe.expectMessageType[StockProtocol.CancelStock]
      }

      "BillingRefへのCancelStockをリトライしクライアントにCreateOrderFailedを返す" in {
        val orderId                   = OrderId()
        val orderItems                = newOrderItems()
        val createOrderReplyTestProbe = testKit.createTestProbe[OrderProtocol.CreateOrderReply]()

        val counter = new AtomicInteger(0)
        val orderProcessManagerRefResult = newOrderProcessManager1Ref(
          orderId,
          BACKOFF_SETTINGS,
          stockHandler = { (ctx, message) =>
            message match {
              case message: StockProtocol.SecureStock =>
                message.replyTo ! StockProtocol.SecureStockSucceeded(UUID.randomUUID(), message.id, message.stockId)
              case message: StockProtocol.CancelStock =>
                if (counter.incrementAndGet() <= 3) {
                  ctx.log.info(s"counter = $counter")
                  throw new Exception(s"異常が発生しました: counter = $counter")
                } else {
                  message.replyTo ! StockProtocol.CancelStockSucceeded(UUID.randomUUID(), message.id, message.stockId)
                }
              case _ => ctx.log.error(s"message: $message")
            }
          },
          billingHandler = { (ctx, message) =>
            message match {
              case message: BillingProtocol.CreateBilling =>
                message.replyTo ! BillingProtocol.CreateBillingFailed(
                  UUID.randomUUID(),
                  message.id,
                  message.billingId,
                  BillingError.CreditError(BillingId())
                )
              case _ => ctx.log.error(s"message: $message")
            }
          }
        )

        orderProcessManagerRefResult.processManagerRef ! OrderProtocol.CreateOrder(
          UUID.randomUUID(),
          orderId,
          orderItems,
          createOrderReplyTestProbe.ref
        )

        val reply = createOrderReplyTestProbe.expectMessageType[OrderProtocol.CreateOrderFailed](
          30.seconds
        )
        assert(orderId == reply.orderId)

        orderProcessManagerRefResult.stockProbe.expectMessageType[StockProtocol.SecureStock]
        orderProcessManagerRefResult.billingProbe.expectMessageType[BillingProtocol.CreateBilling]
        orderProcessManagerRefResult.stockProbe.expectMessageType[StockProtocol.CancelStock]
      }
    }

    "中断しても続きから再開し注文することができる" in {
      val orderId                   = OrderId()
      val orderItems                = newOrderItems()
      val createOrderReplyTestProbe = testKit.createTestProbe[OrderProtocol.CreateOrderReply]()

      val orderProcessManagerRefResult0 = newOrderProcessManager1Ref(orderId, BACKOFF_SETTINGS)

      orderProcessManagerRefResult0.processManagerRef ! OrderProtocol.CreateOrder(
        UUID.randomUUID(),
        orderId,
        orderItems,
        createOrderReplyTestProbe.ref
      )

      // SecureStockを受け取ったら、OrderProcessManagerを停止し再開する
      orderProcessManagerRefResult0.stockProbe.expectMessageType[StockProtocol.SecureStock]
      testKit.stop(orderProcessManagerRefResult0.processManagerRef)
      val orderProcessManagerRefResult1 = newOrderProcessManager1Ref(orderId, BACKOFF_SETTINGS)

      val reply = createOrderReplyTestProbe.expectMessageType[OrderProtocol.CreateOrderSucceeded]
      assert(orderId == reply.orderId)

      orderProcessManagerRefResult1.billingProbe.expectMessageType[BillingProtocol.CreateBilling]
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

  private def newOrderProcessManager1Ref(
      orderId: OrderId,
      backoffSettings: BackoffSettings,
      stockHandler: MessageHandler[StockProtocol.CommandRequest] = DEFAULT_STOCK_HANDLER,
      billingHandler: MessageHandler[BillingProtocol.CommandRequest] = DEFAULT_BILLING_HANDLER
  ): OrderProcessManager1RefResult = {
    val stockRefWithProbe   = newStockActorRef(stockHandler)
    val billingRefWithProbe = newBillingActorRef(billingHandler)

    val processManagerRef =
      testKit.spawn(
        OrderProcessManager1(
          orderId,
          backoffSettings,
          stockRefWithProbe._1,
          billingRefWithProbe._1
        )
      )

    OrderProcessManager1RefResult(
      processManagerRef,
      stockRefWithProbe._1,
      stockRefWithProbe._2,
      billingRefWithProbe._1,
      billingRefWithProbe._2
    )
  }
}
