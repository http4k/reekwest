package cookbook.websockets

import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.routing.bind
import org.http4k.routing.websockets
import org.http4k.websocket.RoutingWsHandler
import org.http4k.websocket.WebSocket
import org.http4k.websocket.WsMessage
import org.http4k.websocket.asClient
import org.http4k.websocket.string

data class Wrapper2(val v: Int)

val body = WsMessage.string().map({ Wrapper2(it.toInt()) }, { it.v.toString() }).toLens()

private val ws: RoutingWsHandler = websockets(
    "/hello" bind websockets(
        "/bob" bind { ws: WebSocket ->
            println("hello bob")
            ws.onMessage {
                val received = body(it)
                println("bob got " + received)
                ws.send(body(Wrapper2(123 * received.v)))
            }
        }
    )
)

fun main(args: Array<String>) {

    val client = ws.asClient(Request(Method.GET, "/hello/bob"))
    client.triggerMessage(WsMessage("1"))
    client.triggerMessage(WsMessage("2"))
    client.close()

    client.received.take(3).forEach {
        println("received back: " + body(it)) }
    }