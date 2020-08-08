package co.firstorderlabs.fakebase.protos

import java.time.{Instant, Duration => JavaDuration}

import com.google.protobuf.duration.{Duration => ProtoDuration}
import scalapb.TypeMapper

package object fakebase {
  implicit val durationTypeMapper =
    TypeMapper[ProtoDuration, JavaDuration](
      protoDuration =>
        JavaDuration.ofSeconds(protoDuration.seconds, protoDuration.nanos)
    )(
      javaDuration =>
        ProtoDuration(
          seconds = javaDuration.getSeconds,
          nanos = javaDuration.getNano
      )
    )

  implicit val instantParser = TypeMapper[String, Instant](timestamp => {
    if (timestamp.length == 0)
      Instant.now
    else
      Instant.parse(timestamp)
  })(instant => instant.toString)
}
