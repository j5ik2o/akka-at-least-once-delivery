package example.simple

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, BehaviorInterceptor, TypedActorContext }
import akka.persistence.typed.PersistenceId
import com.typesafe.config.{ Config, ConfigFactory }
import example.CborSerializable
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.duration.DurationInt

object ForwarderSpec {

  val config: Config = ConfigFactory.parseString(s"""
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.persistence.journal.inmem.test-serialization = on
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/${getClass.getName}-${UUID.randomUUID().toString}"
    akka.actor.serialization-bindings {
      "${classOf[CborSerializable].getName}" = jackson-cbor
    }
    """)

}

class ForwarderSpec extends ScalaTestWithActorTestKit(ForwarderSpec.config) with AnyFreeSpecLike {

  "at-least-onceに対応したForwarder" - {
    "送信したメッセージが受信できる" in {
      val receiverProbe = createTestProbe[Receiver.Command]()
      val receiverRef   = spawn(Behaviors.monitor(receiverProbe.ref, Receiver()))
      val forwarderRef  = spawn(Forwarder(UUID.randomUUID(), receiverRef, 2.seconds))

      // メッセージの転送(1回目)
      forwarderRef ! Forwarder.Forward(Receiver.Message("a"))
      val message1 = receiverProbe.expectMessageType[Receiver.Request]
      message1.deliveryId should ===(1L)
      message1.payload should ===(Receiver.Message("a"))

      // メッセージの転送(2回目)
      forwarderRef ! Forwarder.Forward(Receiver.Message("b"))
      val message2 = receiverProbe.expectMessageType[Receiver.Request]
      message2.deliveryId should ===(2L)
      message2.payload should ===(Receiver.Message("b"))

      // 返信後にメッセージが受信されないこと
      receiverProbe.expectNoMessage(3.seconds)

      testKit.stop(forwarderRef)
    }

    "ロストしたメッセージを再送できる。ただし受信できない" in {
      // 受信側の準備
      // フィルターの外側のプローブ
      val outerReceiverProbe = createTestProbe[Receiver.Command]()
      // フィルターの内側のプローブ
      val innerReceiverProbe = createTestProbe[Receiver.Command]()
      // レシーバーへのアクター参照
      val receiverRef =
        spawn(
          Behaviors.monitor(
            outerReceiverProbe.ref,
            filterMessage(message => message.deliveryId == 3 || message.deliveryId == 4) {
              Behaviors.monitor(innerReceiverProbe.ref, Receiver())
            }
          )
        )

      val id           = UUID.randomUUID()
      val forwarderRef = spawn(Forwarder(id, receiverRef, 2.second))

      // フォワーダーを介してメッセージを送信する
      forwarderRef ! Forwarder.Forward(Receiver.Message("a"))
      forwarderRef ! Forwarder.Forward(Receiver.Message("b"))
      forwarderRef ! Forwarder.Forward(Receiver.Message("c"))
      forwarderRef ! Forwarder.Forward(Receiver.Message("d"))
      forwarderRef ! Forwarder.Forward(Receiver.Message("e"))

      // 1が送信された
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(1L)
      // 1が受信された
      innerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(1L)

      // 2が送信された
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(2L)
      // 2が受信された
      innerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(2L)

      // 3が送信された
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(3L)
      // 4が送信された
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(4L)

      // 3と4がドロップされinnerには届かない

      // 5が送信された
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(5L)
      // 5が受信された(本来であれば届かないで3,4が届くまでstashしたほうがいい)
      innerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(5L)

      // 再送1
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(3L)
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(4L)
      innerReceiverProbe.expectNoMessage() // ドロップされるので届かない

      // 再送2
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(3L)
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(4L)
      innerReceiverProbe.expectNoMessage() // ドロップされるので届かない

      // forwarderを停止
      testKit.stop(forwarderRef)

      // forwarderが再起動した後も、継続的に再送される
      val senderRef2 = spawn(Forwarder(id, receiverRef, 2.second))

      // 再送3
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(3L)
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(4L)
      innerReceiverProbe.expectNoMessage() // ドロップされるので届かない

      // 新たにfを送信する
      senderRef2 ! Forwarder.Forward(Receiver.Message("f"))

      // 6が送信された
      outerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(6L)
      // 6が受信された(本来であれば届かないで3,4,5が届くまでstashしたほうがいい)
      innerReceiverProbe.expectMessageType[Receiver.Request].deliveryId should ===(6L)

      testKit.stop(senderRef2)
    }

  }

  // 指定した条件のメッセージをドロップするビヘイビア
  private def filterMessage(
      f: Receiver.Request => Boolean
  )(destination: Behavior[Receiver.Command]): Behavior[Receiver.Command] = {
    val interceptor = new BehaviorInterceptor[Receiver.Command, Receiver.Command] {
      override def aroundReceive(
          ctx: TypedActorContext[Receiver.Command],
          msg: Receiver.Command,
          target: BehaviorInterceptor.ReceiveTarget[Receiver.Command]
      ): Behavior[Receiver.Command] = {
        msg match {
          case m: Receiver.Request =>
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
