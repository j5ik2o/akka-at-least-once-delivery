package example.processManager

import example.CborSerializable

import java.util.concurrent.atomic.AtomicInteger

final case class Attempt(initial: Int, max: Int) extends CborSerializable {
  private val value = new AtomicInteger(0)

  def increment: Attempt = {
    val result = copy()
    result.value.incrementAndGet()
    if (result.value.intValue() > max) {
      result.value.set(initial)
    }
    result
  }

  def toInt: Int       = value.intValue()
  def toDouble: Double = value.doubleValue()
}
