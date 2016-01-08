package multitier
package reactive

import common._
import common.multitier._
import common.reactive._
import retier._
import retier.rescalaTransmitter._
import retier.serializable.upickle._
import retier.tcp._

import rescala.Var
import rescala.Signal
import makro.SignalMacro.{SignalM => Signal}

@multitier
object PingPong {
  import rescala.conversions.SignalConversions._

  class Server extends Peer {
    type Connection <: Multiple[Client]
    def connect = listen[Client] { TCP(1099) }
  }

  class Client extends Peer {
    type Connection <: Single[Server]
    def connect = request[Server] { TCP("localhost", 1099) }
  }

  val clientMouseY = placed[Client] { implicit! => Signal { UI.mousePosition().y } }

  val isPlaying = placed[Server].local { implicit! =>
    Signal { remote[Client].connected().size >= 2 }
  }

  val ball: Signal[Point] on Server = placed { implicit! =>
    tick.fold(initPosition) { (ball, _) =>
      if (isPlaying.get) ball + speed.get else ball
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
    val racketY = Signal {
      players() map { _ flatMap { client =>
        (clientMouseY from client).asLocal() } getOrElse initPosition.y }
    }

    val leftRacket = new Racket(leftRacketPos, Signal { racketY()(0) })
    val rightRacket = new Racket(rightRacketPos, Signal { racketY()(1) })

    val rackets = List(leftRacket, rightRacket)
    Signal { rackets map { _.area() } }
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
    val x = xBounce toggle (initSpeed.x, -initSpeed.x)
    val y = yBounce toggle (initSpeed.y, -initSpeed.y)
    Signal { Point(x(), y()) }
  }

  val score = placed[Server] { implicit! =>
    val leftPlayerPoints = rightWall.iterate(0) { _ + 1 }
    val rightPlayerPoints = leftWall.iterate(0) { _ + 1 }
    Signal { leftPlayerPoints() + " : " + rightPlayerPoints() }
  }

  val ui = placed[Client].local { implicit! =>
    new UI(areas.asLocal, ball.asLocal, score.asLocal)
  }

  tickStart
}

object PongServer extends App {
  retier.multitier.run[PingPong.Server]
}

object PongClient extends App {
  retier.multitier.run[PingPong.Client]
}