# Http4s Websockets - Factor Integration

An early-stage integration of Http4s Web sockets with [Factor](https://github.com/benhutchison/factor) based actors.

The idea is that a web socket stream of messages can be backed by a Factor with a behavior specified as a
[handler function](./src/main/scala/factor/http4s/WebSocket.scala).

This integration is inspired by @Lasering's [Http4s Akka](https://github.com/Lasering/http4s-akka) integration.
The difference is the complexity of the two integrations is attributable to the simplification of Factor vs Akka IMO.

An working 2 JVM [client-server example](./integrationTest/src/multi-jvm/scala/factor/http4s/integrationtest/FactorHttp4sWebsocketTest.scala)
is included. It currently relies on a version of http4s based on PR 1913,
built against FS2 1.0.0-M1, which is not released publicly. This will mean its not currently runnable for you.

The example uses Akka Streams for the web service client because websocket clients are [not yet supported by Http4s](https://github.com/http4s/http4s/issues/330)

Once published Http4s binaries supports newer versions of FS2 and Cats Effect this project will switch to them.