package factor.http4s.integrationtest


import akka.http.scaladsl.model.ws._
import cats.implicits._
import cats.effect._
import factor._
import factor.http4s._
import mouse.all._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure
import scala.util.Success

/* A 2-JVM integration test that tests: Akka-Http Websocket Client <-> Http4s Server <-> Factor integration
*/
//https://github.com/http4s/http4s/blob/master/examples/blaze/src/main/scala/com/example/http4s/blaze/BlazeWebSocketExample.scala


object FactorHttp4sWebsocketMultiJvmServerNode extends fs2.StreamApp[IO] with org.http4s.dsl.Http4sDsl[IO] {

  import fs2._
  import fs2.StreamApp.ExitCode
  import org.http4s._
  import org.http4s.server.blaze.BlazeBuilder
  import org.http4s.websocket.WebsocketBits._
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._

  implicit val timeout: FiniteDuration = 1.second

  val name = "FactorHtt4sIntegrationTest"
  val localAddress = SystemAddress(name, "127.0.0.1", 3678)
  val system = new FactorSystem(localAddress)

  def route(implicit timer: Timer[IO]): HttpService[IO] = HttpService[IO] {
    case GET -> Root / "n" =>
      val handler = (inc: Int) => (env: (Int, IO[Unit]), s: Int) => {
        val (limit, terminateAction) = env
        val s1 = s + inc;
        val terminate = s1 >= limit

        println(s"Server: Received $inc  Current State: $s Next State: $s1 Terminating: $terminate")

        if (terminate)
          terminateAction.unsafeRunSync()

        IO(s1, Some(s1))
      }
      val decode: WebSocketFrame => Either[String, Int] = {
        case frame: Text => frame.str.parseInt.leftMap(_.toString)
        case other => Either.left(s"Unrecognized frame: $other")
      }
      val encode = (n: Int) => Text(n.toString)
      val initMsg = Some(1)
      FactorWebSocket.handleWithFactorDecodeToStderr(system, name, localAddress, env = (1000, system.terminate), initState = 0,
        handler, decode, encode, initMsg)
  }

  def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, ExitCode] = {
    val timer = Timer[IO]
    for {
      _ <- Stream.eval(
        IO(println(s"server startup"))
          *>
        (timer.sleep(5.seconds) >> IO(println("Server requestShutdown")) >> requestShutdown).start
      )
      exitCode <- BlazeBuilder[IO]
        .bindHttp(8282)
        .withWebSockets(true)
        .mountService(route(timer), "/http4s")
        .serve
    } yield exitCode
  }

}

object FactorHttp4sWebsocketMultiJvmClientNode {

  //https://doc.akka.io/docs/akka-http/current/client-side/websocket-support.html#websocketclientflow

  import akka.actor.ActorSystem
  import akka.Done
  import akka.http.scaladsl.Http
  import akka.stream.ActorMaterializer
  import akka.stream.scaladsl._
  import akka.http.scaladsl.model._
  import akka.http.scaladsl.model.ws._

  import scala.concurrent.Future


  def main(args: Array[String]): Unit = {
    println(s"Client startup")
    //pause to give the server JVM a chance to startup first
    Thread.sleep(1000)

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    import system.dispatcher

    // flow to use (note: not re-usable!)
    val replyFlow = Flow[Message].map {
      case TextMessage.Strict(msg) =>
        val n = msg.toInt
        println(s"Client: $n")
        TextMessage(s"${n % 3}")
    }
    val (upgradeResponse, promise) = Http().singleWebSocketRequest(
      WebSocketRequest("ws://localhost:8282/http4s/n"),
      replyFlow
    )

    val connected = upgradeResponse.flatMap { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        Future.successful(Done)
      } else {
        throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }
    }
    connected.onComplete {
      case Success(done) => println(s"connect OK: $done")
      case Failure(ex) => ex.printStackTrace()
    }

    (Timer[IO].sleep(5.seconds) >> IO(println("Client system.terminate()")) >> IO(system.terminate())).start.unsafeRunSync()

  }

}
