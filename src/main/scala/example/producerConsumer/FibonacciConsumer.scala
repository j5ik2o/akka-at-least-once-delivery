package example.producerConsumer

import akka.actor.typed.delivery.{ ConsumerController, ProducerController }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }

object FibonacciConsumer {
  def apply(): Behavior[Command] = {
    Behaviors.setup { context =>
      val consumerController: ActorRef[ConsumerController.Command[FibonacciConsumer.Command]] =
        context.spawn(ConsumerController[FibonacciConsumer.Command](), "consumerController")
      val deliveryAdapter =
        context.messageAdapter[ConsumerController.Delivery[FibonacciConsumer.Command]](WrappedDelivery)
      consumerController ! ConsumerController.Start(deliveryAdapter)
      Behaviors.receiveMessagePartial {
        case RegisterProducerController(producerController) =>
          consumerController ! ConsumerController.RegisterToProducerController(producerController)
          Behaviors.same
        case WrappedDelivery(ConsumerController.Delivery(FibonacciNumber(n, value), confirmTo)) =>
          context.log.info("Processed fibonacci {}: {}", n, value)
          // 届いたことを返信する
          // 注意:このアクターのプロトコルで返信するわけではない
          confirmTo ! ConsumerController.Confirmed
          Behaviors.same
      }
    }
  }

  sealed trait Command

  final case class FibonacciNumber(n: Long, value: BigInt) extends Command

  private case class WrappedDelivery(d: ConsumerController.Delivery[Command]) extends Command

  final case class RegisterProducerController(
      producerController: ActorRef[ProducerController.Command[FibonacciConsumer.Command]]
  ) extends Command
}
