package example.producerConsumer

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.delivery.ProducerController
import akka.actor.typed.delivery.ProducerController.MessageWithConfirmation
import akka.actor.typed.scaladsl.Behaviors

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

object FibonacciProducer {
  sealed trait Command
  private final case class AskReply(resultId: UUID, timeout: Boolean)                                 extends Command
  private case class WrappedRequestNext(r: ProducerController.RequestNext[FibonacciConsumer.Command]) extends Command

  def apply(producerController: ActorRef[ProducerController.Command[FibonacciConsumer.Command]]): Behavior[Command] = {
    Behaviors.setup { context =>
      val requestNextAdapter =
        context.messageAdapter[ProducerController.RequestNext[FibonacciConsumer.Command]](WrappedRequestNext)
      producerController ! ProducerController.Start(requestNextAdapter)

      fibonacci(0, 1, 0)
    }
  }

  import akka.util.Timeout
  implicit val askTimeout: Timeout = 5.seconds

  private def fibonacci(n: Long, b: BigInt, a: BigInt): Behavior[Command] = {
    Behaviors.receive {
      case (context, WrappedRequestNext(next)) =>
        context.log.info("Generated fibonacci {}: {}", n, a)
//       next.sendNextTo ! FibonacciConsumer.FibonacciNumber(n, a)
        val resultId = UUID.randomUUID()
        context.ask[MessageWithConfirmation[FibonacciConsumer.Command], Long](
          next.askNextTo,
          askReplyTo => MessageWithConfirmation(FibonacciConsumer.FibonacciNumber(n, a), askReplyTo)
        ) {
          case Success(_) => AskReply(resultId, timeout = false)
          case Failure(_) => AskReply(resultId, timeout = true)
        }
        Behaviors.same
      case (_, AskReply(_, to)) =>
        if (n == 1000)
          Behaviors.stopped
        else
          fibonacci(n + 1, a + b, b)
    }
  }
}
