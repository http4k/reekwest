package org.http4k.filter

import com.natpryce.hamkrest.MatchResult
import com.natpryce.hamkrest.MatchResult.Mismatch
import com.natpryce.hamkrest.Matcher
import com.natpryce.hamkrest.and
import com.natpryce.hamkrest.assertion.assertThat
import io.opentelemetry.common.Labels
import io.opentelemetry.sdk.metrics.data.MetricData
import org.http4k.core.Method.DELETE
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.Status.Companion.OK
import org.http4k.hamkrest.hasBody
import org.http4k.hamkrest.hasStatus
import org.http4k.lens.Path
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.util.TickingClock
import org.junit.jupiter.api.Test

class OpenTelemetryMetricsServerTest {
    private val clock = TickingClock
    private var requestTimer = ServerFilters.OpenTelemetryMetrics.RequestTimer(clock = clock)
    private var requestCounter = ServerFilters.OpenTelemetryMetrics.RequestCounter(clock = clock)
    private val server by lazy {
        routes(
            "/timed" bind routes(
                "/one" bind GET to { Response(OK) },
                "/two/{name:.*}" bind POST to { Response(OK).body(Path.of("name")(it)) }
            ).withFilter(requestTimer),
            "/counted" bind routes(
                "/one" bind GET to { Response(OK) },
                "/two/{name:.*}" bind POST to { Response(OK).body(Path.of("name")(it)) }
            ).withFilter(requestCounter),
            "/unmetered" bind routes(
                "one" bind GET to { Response(OK) },
                "two" bind DELETE to { Response(INTERNAL_SERVER_ERROR) }
            ),
            "/otherTimed" bind static().withFilter(requestTimer),
            "/otherCounted" bind static().withFilter(requestCounter)
        )
    }

    @Test
    fun `routes with timer generate request timing metrics tagged with path and method and status`() {
        assertThat(server(Request(GET, "/timed/one")), hasStatus(OK))
        repeat(2) {
            assertThat(server(Request(POST, "/timed/two/bob")), (hasStatus(OK) and hasBody("bob")))
        }

        val data = exportMetricsFromOpenTelemetry()
        assertThat(data, hasRequestLatency(1, 1000.0, Labels.of("path", "timed_one", "method", "GET", "status", "200")))
        assertThat(data, hasRequestLatency(2, 2000.0, Labels.of("path", "timed_two_name", "method", "POST", "status", "200")))
    }

    @Test
    fun `routes with counter generate request count metrics tagged with path and method and status`() {
        assertThat(server(Request(GET, "/counted/one")), hasStatus(OK))
        repeat(2) {
            assertThat(server(Request(POST, "/counted/two/bob")), (hasStatus(OK) and hasBody("bob")))
        }

        val data = exportMetricsFromOpenTelemetry()
        assertThat(data, hasRequestCounter(1, Labels.of("path", "counted_one", "method", "GET", "status", "200")))
        assertThat(data, hasRequestCounter(2, Labels.of("path", "counted_two_name", "method", "POST", "status", "200")))
    }

    private fun hasRequestLatency(count: Int, value: Double, labels: Labels) =
        object : Matcher<List<MetricData>> {
            override val description = "http.server.request.latency"

            override fun invoke(actual: List<MetricData>): MatchResult {
                val summary = actual
                    .first { it.name == description }
                    .points
                    .first { it.labels == labels } as MetricData.SummaryPoint
                return if (
                    summary.count != count.toLong() &&
                    summary.percentileValues.last().value != value
                ) Mismatch(actual.toString())
                else MatchResult.Match
            }
        }

    private fun hasRequestCounter(count: Int, labels: Labels) =
        object : Matcher<List<MetricData>> {
            override val description = "http.server.request.count"

            override fun invoke(actual: List<MetricData>): MatchResult {
                val counter = actual
                    .first { it.name == description }
                    .points
                    .first { it.labels == labels } as MetricData.LongPoint
                return if (counter.value == count.toLong()) MatchResult.Match else Mismatch(actual.toString())
            }
        }
//
//    @Test
//    fun `routes with counter generate request count metrics tagged with path and method and status`() {
//        assertThat(server(Request(GET, "/counted/one")), hasStatus(OK))
//        repeat(2) {
//            assertThat(server(Request(POST, "/counted/two/bob")), (hasStatus(OK) and hasBody("bob")))
//        }
//
//        assert(registry,
//            hasRequestCounter(1, tags = arrayOf("path" to "counted_one", "method" to "GET", "status" to "200")),
//            hasRequestCounter(2, tags = arrayOf("path" to "counted_two_name", "method" to "POST", "status" to "200"))
//        )
//    }
//
//    @Test
//    fun `routes without metrics generate nothing`() {
//        assertThat(server(Request(GET, "/unmetered/one")), hasStatus(OK))
//        assertThat(server(Request(DELETE, "/unmetered/two")), hasStatus(INTERNAL_SERVER_ERROR))
//
//        assert(registry,
//            hasNoRequestTimer(GET, "unmetered_one", OK),
//            hasNoRequestTimer(DELETE, "unmetered_two", INTERNAL_SERVER_ERROR),
//            hasNoRequestCounter(GET, "unmetered_one", OK),
//            hasNoRequestCounter(DELETE, "unmetered_two", INTERNAL_SERVER_ERROR)
//        )
//    }
//
//    @Test
//    fun `request timer meter names and request id formatter can be configured`() {
//        requestTimer = ServerFilters.OpenTelemetryMetrics.RequestTimer(registry, "custom.requests", "custom.description",
//            { it.label("foo", "bar") }, clock)
//
//        assertThat(server(Request(GET, "/timed/one")), hasStatus(OK))
//
//        assert(registry,
//            hasRequestTimer(1, 1, "custom.requests", "custom.description", "foo" to "bar")
//        )
//    }
//
//    @Test
//    fun `request counter meter names and request id formatter can be configured`() {
//        requestCounter = ServerFilters.OpenTelemetryMetrics.RequestCounter(registry, "custom.requests", "custom.description",
//            { it.label("foo", "bar") })
//
//        assertThat(server(Request(GET, "/counted/one")), hasStatus(OK))
//
//        assert(registry,
//            hasRequestCounter(1, "custom.requests", "custom.description", "foo" to "bar")
//        )
//    }
//
//    @Test
//    fun `timed routes without uri template generate request timing metrics tagged with unmapped path value`() {
//        assertThat(server(Request(GET, "/otherTimed/test.json")), hasStatus(OK))
//
//        assert(registry, hasRequestTimer(1, 1, tags = arrayOf("path" to "UNMAPPED", "method" to "GET", "status" to "200")))
//    }
//
//    @Test
//    fun `counted routes without uri template generate request count metrics tagged with unmapped path value`() {
//        assertThat(server(Request(GET, "/otherCounted/test.json")), hasStatus(OK))
//        assert(registry, hasRequestCounter(1, tags = arrayOf("path" to "UNMAPPED", "method" to "GET", "status" to "200")))
//    }
//
//
//    private fun hasRequestCounter(count: Long,
//                                  name: String = "http.server.request.count",
//                                  description: String = "Total number of server requests",
//                                  vararg tags: Pair<String, String>) = hasCounter(name,
//        tags.asList()
//            .map { Tag.of(it.first, it.second) },
//        description(description) and counterCount(count)
//    )
//
//    private fun hasRequestTimer(count: Long, totalTimeSec: Long,
//                                name: String = "http.server.request.latency",
//                                description: String = "Timing of server requests",
//                                vararg tags: Pair<String, String>) = hasTimer(name,
//        tags.asList()
//            .map { Tag.of(it.first, it.second) },
//        description(description) and timerCount(count) and timerTotalTime(totalTimeSec * 1000)
//    )
//
//    private fun hasNoRequestTimer(method: Method, path: String, status: Status) =
//        !hasTimer("http.server.request.latency",
//            listOf(Tag.of("path", path), Tag.of("method", method.name), Tag.of("status", status.code.toString()))
//        )
//
//    private fun hasNoRequestCounter(method: Method, path: String, status: Status) =
//        !hasCounter("http.server.request.count",
//            listOf(Tag.of("path", path), Tag.of("method", method.name), Tag.of("status", status.code.toString()))
//        )
}
