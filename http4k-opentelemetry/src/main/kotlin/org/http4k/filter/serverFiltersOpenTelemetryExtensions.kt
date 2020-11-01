package org.http4k.filter

import io.grpc.Context.current
import io.opentelemetry.OpenTelemetry
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.trace.Span.Kind.SERVER
import io.opentelemetry.trace.StatusCanonicalCode.ERROR
import io.opentelemetry.trace.Tracer
import io.opentelemetry.trace.TracingContextUtils.currentContextWith
import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.routing.RoutedRequest

fun ServerFilters.OpenTelemetryTracing(tracer: Tracer,
                                       spanNamer: (Request) -> String = { it.uri.toString() },
                                       error: (Request, Throwable) -> String = { _, t -> t.localizedMessage }
): Filter {
    val textMapPropagator = OpenTelemetry.getPropagators().textMapPropagator

    val getter = TextMapPropagator.Getter<Request> { req, name -> req.header(name) }

    return Filter { next ->
        { req ->
            with(tracer.spanBuilder(spanNamer(req))
                .setParent(textMapPropagator.extract(current(), req, getter))
                .setSpanKind(SERVER)
                .startSpan()
            ) {
                setAttribute("http.method", req.method.name)
                setAttribute("http.url", req.uri.toString())
                if (req is RoutedRequest) setAttribute("http.route", req.xUriTemplate.toString())

                currentContextWith(this).use {
                    try {
                        next(req)
                    } catch (t: Throwable) {
                        setStatus(ERROR, error(req, t))
                        throw t
                    } finally {
                        end()
                    }
                }
            }

        }
    }
}
