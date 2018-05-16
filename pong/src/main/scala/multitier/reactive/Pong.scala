package multitier
package reactive

import common._
import common.multitier._
import common.reactive._
import loci._
import loci.transmitter.rescala._
import loci.serializer.upickle._
import loci.communicator.tcp._

import rescala._

@multitier
object PingPong {
  trait Server extends Peer { type Tie <: Multiple[Client] }
  trait Client extends Peer with FrontEndHolder { type Tie <: Single[Server] }

  val clientMouseY = placed[Client] { implicit! => Signal.dynamic { peer.mousePosition().y } }

  val isPlaying = placed[Server].local { implicit! =>
    Signal { remote[Client].connected().size >= 2 }
  }

  val ball: Signal[Point] on Server = placed { implicit! =>
    Events.foldOne(tick, initPosition) { (ball, _) =>
      if (isPlaying.readValueOnce) ball + speed.readValueOnce else ball
    }
  }

  val players = placed[Server].local { implicit! =>
    Signal {
      remote[Client].connected() match {
        case left :: right :: _ => Seq(Some(left), Some(right))
        case _ => Seq(None, None)
      }
    }
  }

  val areas = placed[Server] { implicit! =>
    val racketY = Signal.dynamic {
      players() map { _ map { client =>
        (clientMouseY from client).asLocal() } getOrElse initPosition.y }
    }

    val leftRacket = new Racket(leftRacketPos, Signal { racketY()(0) })
    val rightRacket = new Racket(rightRacketPos, Signal { racketY()(1) })

    val rackets = List(leftRacket, rightRacket)
    Signal.dynamic { rackets map { _.area() } }
  }

  val leftWall = placed[Server].local { implicit! => ball.changed && { _.x < 0 } }
  val rightWall = placed[Server].local { implicit! => ball.changed && { _.x > maxX } }

  val xBounce = placed[Server].local { implicit! =>
    val ballInRacket = Signal { areas() exists { _ contains ball() } }
    val collisionRacket = ballInRacket changedTo true
    leftWall || rightWall || collisionRacket
  }

  val yBounce = placed[Server].local { implicit! =>
    ball.changed && { ball => ball.y < 0 || ball.y > maxY }
  }

  val speed = placed[Server].local { implicit! =>
    val x = xBounce toggle (Signal { initSpeed.x }, Signal { -initSpeed.x })
    val y = yBounce toggle (Signal { initSpeed.y }, Signal { -initSpeed.y })
    Signal { Point(x(), y()) }
  }

  val score = placed[Server] { implicit! =>
    val leftPlayerPoints = rightWall.iterate(0) { _ + 1 }
    val rightPlayerPoints = leftWall.iterate(0) { _ + 1 }
    Signal { leftPlayerPoints() + " : " + rightPlayerPoints() }
  }

  val frontEnd = placed[Client].local { implicit! =>
    peer.createFrontEnd(areas.asLocal, ball.asLocal, score.asLocal)
  }

  tickStart
}

object PongServer extends App {
  loci.multitier setup new PingPong.Server {
    def connect = listen[PingPong.Client] { TCP(1099) }
  }
}

object PongClient extends App {
  loci.multitier setup new PingPong.Client with UI.FrontEnd {
    def connect = connect[PingPong.Server] { TCP("localhost", 1099) }
  }
}

object PongClientBenchmark extends App {
  loci.multitier setup new PingPong.Client with Benchmark.FrontEnd {
    def connect = connect[PingPong.Server] { TCP("localhost", 1099) }
    def arguments = args
  }
}
