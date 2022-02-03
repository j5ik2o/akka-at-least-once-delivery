package example.delivery

import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.actor.typed.delivery.{ ConsumerController, ProducerController }
import akka.actor.typed.scaladsl.Behaviors

object Main extends App {
  val main = Behaviors.setup[Any] { context =>
    val consumerRef = context.spawn(FibonacciConsumer(), "consumer")
    context.spawn(FibonacciProducer(consumerRef), "producer")
    Behaviors.same
  }
  ActorSystem(main, "main")
}
