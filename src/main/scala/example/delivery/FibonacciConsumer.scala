package example.delivery

import akka.actor.typed.delivery.{ ConsumerController, ProducerController }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }

object FibonacciConsumer {
  sealed trait Command

  final case class FibonacciNumber(n: Long, value: BigInt) extends Command

  private case class WrappedDelivery(d: ConsumerController.Delivery[Command]) extends Command

  final case class RegisterProducerController(
      producerController: ActorRef[ProducerController.Command[FibonacciConsumer.Command]]
  ) extends Command

  def apply(): Behavior[Command] = {
    Behaviors.setup { context =>
      // コンシューマコントローラを生成する
      val consumerController: ActorRef[ConsumerController.Command[FibonacciConsumer.Command]] =
        context.spawn(ConsumerController[FibonacciConsumer.Command](), "consumerController")
      // メッセージアダプタを生成する
      val deliveryAdapter =
        context.messageAdapter[ConsumerController.Delivery[FibonacciConsumer.Command]](WrappedDelivery)
      // コンシューマコントローラを開始する
      consumerController ! ConsumerController.Start(deliveryAdapter)
      Behaviors.receiveMessagePartial {
        // プロデューサコントローラの登録要求
        case RegisterProducerController(producerController) =>
          consumerController ! ConsumerController.RegisterToProducerController(producerController)
          Behaviors.same
        // コンシューマコントローラからメッセージが届いたら
        case WrappedDelivery(ConsumerController.Delivery(FibonacciNumber(n, value), confirmTo)) =>
          context.log.info("Processed fibonacci {}: {}", n, value)
          // 届いたことをコンシューマコントローラへ返信する
          // 注意:このアクターのプロトコルで返信するわけではない
          confirmTo ! ConsumerController.Confirmed
          Behaviors.same
      }
    }
  }

}
