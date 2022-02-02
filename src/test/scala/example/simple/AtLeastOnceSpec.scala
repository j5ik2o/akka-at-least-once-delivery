package example.simple

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, BehaviorInterceptor, TypedActorContext }
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import example.CborSerializable
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.duration.DurationInt

object AtLeastOnceSpec {

  val config = ConfigFactory.parseString(s"""
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.persistence.journal.inmem.test-serialization = on
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/AtLeastOnceSpec-${UUID.randomUUID().toString}"
    akka.actor.serialization-bindings {
      "${classOf[CborSerializable].getName}" = jackson-cbor
    }
    """)

}

class AtLeastOnceSpec extends ScalaTestWithActorTestKit(AtLeastOnceSpec.config) with AnyWordSpecLike {

  "AtLeastOnce with Persistence Typed" must {
    "deliver and confirm when no message loss" in {
      val destinationProbe = createTestProbe[Destination.Command]()
      val destination      = spawn(Behaviors.monitor(destinationProbe.ref, Destination()))
      val sender           = spawn(Forwarder(PersistenceId.ofUniqueId("pid1"), destination, 2.seconds))

      sender ! Forwarder.Forward(Destination.Payload("a"))
      val msg1 = destinationProbe.expectMessageType[Destination.Request]
      msg1.deliveryId should ===(1L)
      msg1.payload should ===(Destination.Payload("a"))

      sender ! Forwarder.Forward(Destination.Payload("b"))
      val msg2 = destinationProbe.expectMessageType[Destination.Request]
      msg2.deliveryId should ===(2L)
      msg2.payload should ===(Destination.Payload("b"))

      // no redelivery after confirmation
      // 確認後の再配達不可
      destinationProbe.expectNoMessage(3.seconds)

      testKit.stop(sender)
    }

    "redeliver lost messages" in {
      val outerDestinationProbe = createTestProbe[Destination.Command]()
      val innerDestinationProbe = createTestProbe[Destination.Command]()
      val destination =
        spawn(
          Behaviors.monitor(
            outerDestinationProbe.ref,
            filterMessage(m => m.deliveryId == 3 || m.deliveryId == 4) {
              Behaviors.monitor(innerDestinationProbe.ref, Destination())
            }
          )
        )
      val sender = spawn(Forwarder(PersistenceId.ofUniqueId("pid2"), destination, 2.second))

      sender ! Forwarder.Forward(Destination.Payload("a"))
      sender ! Forwarder.Forward(Destination.Payload("b"))
      sender ! Forwarder.Forward(Destination.Payload("c"))
      sender ! Forwarder.Forward(Destination.Payload("d"))
      sender ! Forwarder.Forward(Destination.Payload("e"))

      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(1L)
      innerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(1L)

      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(2L)
      innerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(2L)

      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(3L)
      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(4L)

      // 3 and 4 dropped and not delivered to innerDestinationProbe
      // 3と4がドロップされ、innerDestinationProbeに配信されない。
      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(5L)
      innerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(5L)

      // redelivered
      // 再送
      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(3L)
      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(4L)
      innerDestinationProbe.expectNoMessage() // but still dropped, しかしそれでもドロップされた

      // redelivered again
      // 再再送
      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(3L)
      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(4L)
      innerDestinationProbe.expectNoMessage() // but still dropped, しかしそれでもドロップされた

      // senderを停止
      testKit.stop(sender)

      // and redelivery should continue after recovery, same pid
      // で、復旧後も再配信を継続する必要があり、同じpid
      val sender2 = spawn(Forwarder(PersistenceId.ofUniqueId("pid2"), destination, 2.second))

      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(3L)
      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(4L)
      innerDestinationProbe.expectNoMessage() // but still dropped, しかしそれでもドロップされた

      sender2 ! Forwarder.Forward(Destination.Payload("f"))
      outerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(6L)
      innerDestinationProbe.expectMessageType[Destination.Request].deliveryId should ===(6L)

      testKit.stop(sender2)
    }

  }

  private def filterMessage(
      f: Destination.Request => Boolean
  )(destination: Behavior[Destination.Command]): Behavior[Destination.Command] = {
    val interceptor = new BehaviorInterceptor[Destination.Command, Destination.Command] {
      override def aroundReceive(
          ctx: TypedActorContext[Destination.Command],
          msg: Destination.Command,
          target: BehaviorInterceptor.ReceiveTarget[Destination.Command]
      ): Behavior[Destination.Command] = {
        msg match {
          case m: Destination.Request =>
            if (f(m)) {
              ctx.asScala.log.info("Dropped #{}", m.deliveryId)
              Behaviors.same
            } else
              target(ctx, msg)
          case _ => target(ctx, msg)
        }

      }
    }
    Behaviors.intercept(() => interceptor)(destination)
  }

}
