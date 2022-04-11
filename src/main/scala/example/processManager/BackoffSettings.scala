package example.processManager

import scala.concurrent.duration.FiniteDuration

final case class BackoffSettings(
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double
)
