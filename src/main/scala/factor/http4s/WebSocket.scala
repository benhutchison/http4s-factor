package factor.http4s

import scala.concurrent.duration._
import scala.reflect._
import factor._
import cats._
import cats.data._
import cats.implicits._
import cats.effect._
import fs2._
import fs2.async._
import fs2.async.mutable._
import org.http4s.server.websocket._
import org.http4s.websocket.WebsocketBits._
import org.http4s.{Status, Response}
import mouse.all._

import scala.concurrent._
import scala.concurrent.duration.Duration.Infinite
import scala.util.Random

object FactorWebSocket {
  /**
    * Build a response which will accept an HTTP websocket upgrade request and initiate a websocket connection.
    * Each WebSocket will backed by a new Factor, created either locally or on a remote FactorSystem
    *
    * The initial environment, state and handler for each incoming message are all specified by the caller.
    *
    * The messages received from the websocket will be converted to type `In` via the `decode` function and then sent
    * to the factor. Similarly the messages the actor sends will be converted from type Out` via the `encode` function.
    *
    * @tparam E the Factor environment
    * @tparam S the Factor state
    * @tparam In the type of messages coming in
    * @tparam Out the type of messages going out
    *
    * @param system local FactorSystem
    * @param namePrefix factor names are based on this with a random long int suffix
    * @param factorAddress address of the machine where Factors are created
    * @param env Environment passed to each Factor
    * @param initState initial state of each Factor
    * @param handler behavioral function of the factor. Takes the attempt to decode an incoming message from the socket,
    *                plus the current Factor env and state, and returns a `HandlerResult`, which describes:
    *                - new state
    *                - an optional reply of type `Out` to be sent to client thru websocket
    *                - a flag to tell the framework to close the socket and stop the factor
    *
    * @param decode decoder of incoming messages
    * @param encode encoder of outgoing messages
    * @param initMessage an optional message that can be sent on the socket before and independent of any incoming messages
    * @param config optional queue size and inactivity timeout config
    */
  def handleWithFactor[E, S, In, Out : ClassTag](
    system: FactorSystem,
    namePrefix: String,
    factorAddress: SystemAddress,
    env: E,
    initState: S,
    handler: Either[String, In] => (E, S) => IO[HandlerResult[S, Out]],
    decode: WebSocketFrame => Either[String, In],
    encode: Out => WebSocketFrame,
    initMessage: Option[Out] = None,
    config: ActorWSConfig = ActorWSConfig()
    )
   (implicit
     timeout: FiniteDuration,
     timer: Timer[IO],
     ec: ExecutionContext): IO[Response[IO]] =
  {

    for {
      rng <- IO(Random.nextLong())

      factor <- IO(system.createFactor(env, initState, s"$namePrefix-$rng", factorAddress))

      signal <- async.signalOf[IO, Boolean](false)

      queue <- config.queueSize.fold[IO[Queue[IO, Out]]](async.unboundedQueue)(async.boundedQueue(_))

      _ <- initMessage.fold(IO.unit)(out => queue.enqueue1(out))

      // When the serverActor terminates the clientActor will indicate it via the close signal
      // we use it to interrupt the actorMessages and close the websocket from the server side
      actorMessages: Stream[IO, WebSocketFrame] = queue.dequeue.map(encode).interruptWhen(signal) ++ Stream[WebSocketFrame](Close())

      messagesToBrowser: Stream[IO, WebSocketFrame] = config.serverAliveInterval match {
        case interval: FiniteDuration =>
          // As soon as the actorMessages stop we also stop the pings.
          actorMessages mergeHaltL Stream.awakeEvery[IO](interval).map(_ => Ping())

        case _: Infinite =>
          actorMessages
      }
      
      messagesFromBrowser: Sink[IO, WebSocketFrame] = _.map { frame: WebSocketFrame =>
        factor.run(handler(decode(frame))).flatMap {
          case (optReply, closeWebSocket) =>
              optReply.map(queue.enqueue1(_)).sequence_ >>
                closeWebSocket.option(signal.set(true)).sequence_
          }.handleErrorWith(t => IO(t.printStackTrace())).unsafeRunSync()
      }.onFinalize(IO(factor.stop))


      response <- WebSocketBuilder[IO].build(messagesToBrowser, messagesFromBrowser)
    } yield (response)
  }

  /** A variant of handleWithFactor that logs failed decodes to stderr, allowing for a simplified handler function */
  def handleWithFactorDecodeToStderr[E, S, In, Out : ClassTag](
    system: FactorSystem,
    namePrefix: String,
    factorAddress: SystemAddress,
    env: E,
    initState: S,
    handler: In => (E, S) => IO[HandlerResult[S, Out]],
    decode: WebSocketFrame => Either[String, In],
    encode: Out => WebSocketFrame,
    initMessage: Option[Out] = None,
    config: ActorWSConfig = ActorWSConfig()
  )
    (implicit
      timeout: FiniteDuration,
      timer: Timer[IO],
      ec: ExecutionContext): IO[Response[IO]] =
    handleWithFactor(system, namePrefix, factorAddress, env, initState,
      {
        case (Right(in)) =>
          handler(in)
        case Left(msg) =>
          System.err.println(s"Failed to decode WebSocketFrame: $msg")
          (_, s) => IO.pure(Result(s, None))
      }: Either[String, In] => (E, S) => IO[HandlerResult[S, Out]],
      decode, encode, initMessage, config
    )

}

sealed trait HandlerResult[S, Out] extends Product2[S, (Option[Out], Boolean)] {
  def state: S
  def optReply: Option[Out]
  def closeWebSocket: Boolean

  def _1: S = state
  def _2: (Option[Out], Boolean) = (optReply, closeWebSocket)
}
case class Result[S, Out](state: S, optReply: Option[Out], closeWebSocket: Boolean = false) extends HandlerResult[S, Out]
case class StatelessResult[Out](optReply: Option[Out], closeWebSocket: Boolean = false) extends HandlerResult[Unit, Out] {
  def state = ()
}

/**
  * @param queueSize the size of the queue used to store the messages the actor wants to send to the browser.
  *                  Set to `None` to use an unbounded queue.
  * @param serverAliveInterval The websocket will be automatically closed by the browser if no message is sent within a
  *                            timeout. If you would like to keep the websocket open set this to a Duration inferior to
  *                            the timeout. Set to an infinite duration to disable it.
  */
case class ActorWSConfig(
  queueSize: Option[Int] = Some(100),
  serverAliveInterval: Duration = Duration.Inf)
