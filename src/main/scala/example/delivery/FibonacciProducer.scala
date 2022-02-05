package example.delivery

import akka.actor.typed.delivery.ProducerController
import akka.actor.typed.delivery.ProducerController.MessageWithConfirmation
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import example.delivery.FibonacciConsumer.RegisterProducerController

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

object FibonacciProducer {
  sealed trait Command
  private final case class AskReply(timeout: Boolean)                                                 extends Command
  private case class WrappedRequestNext(r: ProducerController.RequestNext[FibonacciConsumer.Command]) extends Command

  def apply(consumerRef: ActorRef[FibonacciConsumer.Command]): Behavior[Command] = {
    Behaviors.setup { context =>
      // 注意:リトライをat-least-onceにするにはEventSourcedProducerQueueを使う必要がある
      //    import akka.persistence.typed.delivery.EventSourcedProducerQueue
      //    import akka.persistence.typed.PersistenceId
      //    val durableQueue =
      //      EventSourcedProducerQueue[FibonacciConsumer.Command](PersistenceId.ofUniqueId("producerDurableQueue"))
      //    val durableQueueBehavior = Some(durableQueue)

      // Noneはインメモリでしか状態を保存しないので、ノード障害などに耐性がなくなる。
      val durableQueueBehavior = None

      // プロデューサコントローラを生成する
      val producerId = s"fibonacci-${UUID.randomUUID()}"
      val producerController: ActorRef[ProducerController.Command[FibonacciConsumer.Command]] = context.spawn(
        ProducerController[FibonacciConsumer.Command](producerId, durableQueueBehavior),
        "producerController"
      )

      // コンシューマ内部のコンシューマコントローラにプロデューサーコントローラを登録させる
      consumerRef ! RegisterProducerController(producerController)

      // メッセージアダプタを生成する
      val requestNextAdapter =
        context.messageAdapter[ProducerController.RequestNext[FibonacciConsumer.Command]](WrappedRequestNext)
      // プロデューサコントローラを開始する
      producerController ! ProducerController.Start(requestNextAdapter)

      fibonacciTell(0, 1, 0)
    }
  }

  // tellする場合
  private def fibonacciTell(n: Long, b: BigInt, a: BigInt): Behavior[Command] = {
    Behaviors.receive {
      // プロデューサコントローラからメッセージがきたら
      case (context, WrappedRequestNext(next)) =>
        context.log.info("Generated fibonacci {}: {}", n, a)
        // プロデューサコントローラにメッセージを送信する
        next.sendNextTo ! FibonacciConsumer.FibonacciNumber(n, a)
        if (n == 1000)
          Behaviors.stopped
        else
          fibonacciTell(n + 1, a + b, b)
    }
  }

  // askする場合
  private def fibonacciAsk(n: Long, b: BigInt, a: BigInt): Behavior[Command] = {
    Behaviors.receive {
      case (context, WrappedRequestNext(next)) =>
        context.log.info("Generated fibonacci {}: {}", n, a)
        import akka.util.Timeout
        implicit val askTimeout: Timeout = 5.seconds
        // 注意:返信はProducerController.SeqNrであってConsumerの返答ではない
        context.ask[MessageWithConfirmation[FibonacciConsumer.Command], ProducerController.SeqNr](
          next.askNextTo,
          askReplyTo => MessageWithConfirmation(FibonacciConsumer.FibonacciNumber(n, a), askReplyTo)
        ) {
          case Success(_) => AskReply(timeout = false)
          case Failure(_) => AskReply(timeout = true)
        }
        Behaviors.same
      case (context, AskReply(timeout)) =>
        if (timeout) {
          context.log.info("Ask failure: fibonacci {}: {}", n, a)
          Behaviors.stopped
        } else {
          context.log.info("Ask success: fibonacci {}: {}", n, a)
          if (n == 1000)
            Behaviors.stopped
          else
            fibonacciAsk(n + 1, a + b, b)
        }
    }
  }
}
