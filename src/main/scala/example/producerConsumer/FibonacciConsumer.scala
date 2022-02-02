package example.producerConsumer

import akka.actor.typed.delivery.ConsumerController
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }

object FibonacciConsumer {
  def apply(consumerController: ActorRef[ConsumerController.Command[FibonacciConsumer.Command]]): Behavior[Command] = {
    Behaviors.setup { context =>
      val deliveryAdapter =
        context.messageAdapter[ConsumerController.Delivery[FibonacciConsumer.Command]](WrappedDelivery(_))
      consumerController ! ConsumerController.Start(deliveryAdapter)

      Behaviors.receiveMessagePartial {
        case WrappedDelivery(ConsumerController.Delivery(FibonacciNumber(n, value), confirmTo)) =>
          context.log.info("Processed fibonacci {}: {}", n, value)
          // 届いたことを返信する
          confirmTo ! ConsumerController.Confirmed
          Behaviors.same
      }
    }
  }

  sealed trait Command

  final case class FibonacciNumber(n: Long, value: BigInt) extends Command

  private case class WrappedDelivery(d: ConsumerController.Delivery[Command]) extends Command
}
