package example.delivery

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors

object Main extends App {
  val mainBehavior = Behaviors.setup[Any] { context =>
    val consumerRef = context.spawn(FibonacciConsumer(), "consumer")
    context.spawn(FibonacciProducer(consumerRef), "producer")
    Behaviors.same
  }
  ActorSystem(mainBehavior, "main")
}
