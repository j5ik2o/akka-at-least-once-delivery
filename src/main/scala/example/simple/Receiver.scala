package example.simple

import akka.actor.typed.scaladsl.{ Behaviors, LoggerOps }
import akka.actor.typed.{ ActorRef, Behavior }
import example.CborSerializable

/** 宛先のアクター
  */
object Receiver {

  sealed trait Command

  final case class Request(deliveryId: Long, payload: Payload, replyTo: ActorRef[Reply])
      extends Command
      with CborSerializable

  final case class Payload(s: String)

  final case class Reply(deliveryId: Long) extends CborSerializable

  def apply(): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case Request(deliveryId, payload, replyTo) =>
          context.log.info2("Received #{}: {}", deliveryId, payload.s)
          replyTo ! Reply(deliveryId)
          Behaviors.same

      }
    }

}
