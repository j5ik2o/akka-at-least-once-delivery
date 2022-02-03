package example.producerConsumer

import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.actor.typed.delivery.{ ConsumerController, ProducerController }
import akka.actor.typed.scaladsl.Behaviors

object Main extends App {
  val behavior = Behaviors.setup[Any] { context =>
    val consumerRef: ActorRef[FibonacciConsumer.Command] = context.spawn(FibonacciConsumer(), "consumer")

    context.spawn(FibonacciProducer(consumerRef), "producer")

    Behaviors.same
  }
  ActorSystem(behavior, "app")
}
