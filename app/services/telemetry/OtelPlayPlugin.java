package services.telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.ClientAttributes;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import org.jspecify.annotations.Nullable;
import play.PlayPlugin;
import play.mvc.Http;
import play.mvc.Router;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records one SERVER span per action invocation (JCLAW-34). Listed in
 * {@code conf/play.plugins}; reads only {@link TelemetryState}, never
 * {@link OtelRuntime} — see {@code TelemetryState} for why that matters.
 *
 * <p>The span is bracketed by {@code beforeActionInvocation} and
 * {@code onActionInvocationFinally}, the two hooks the action invoker fires on every path
 * it has — a Netty request and a test-harness request alike — with the current request
 * set and the action resolved. ({@code onRequestRouting} fires only when the invoker
 * routes the request itself, so a request that arrives pre-routed never reaches it.) One
 * span per invocation is exact because the fork's {@code await(Future)} blocks the
 * virtual thread (PF-121) instead of suspending and re-invoking, so a thread-local
 * carries the span between the two hooks.
 */
public class OtelPlayPlugin extends PlayPlugin {

    private record Active(Span span, Scope scope) {}

    private static final ThreadLocal<@Nullable Active> ACTIVE = new ThreadLocal<>();
    // method + action → route pattern, so /api/config/{key} is one span name however many keys are read.
    private static final ConcurrentHashMap<String, String> ROUTE_PATTERNS = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("MustBeClosed") // the Scope is closed in onActionInvocationFinally, same thread
    public void beforeActionInvocation(Method actionMethod) {
        var request = Http.Request.current();
        if (request == null || ACTIVE.get() != null || !TelemetryState.enabled) {
            return;
        }
        var route = routePattern(request.method, request.action);
        if (TelemetryState.agentAttached) {
            // The agent's own server span reaches this thread through its executor instrumentation;
            // naming it from the route beats opening a second server span beside it.
            var current = Span.current();
            if (current.getSpanContext().isValid()) {
                current.updateName(request.method + " " + (route != null ? route : request.action));
                if (route != null) {
                    current.setAttribute(HttpAttributes.HTTP_ROUTE, route);
                }
                return;
            }
        }
        var builder = TelemetryState.api.getTracer("jclaw").spanBuilder(request.method + " " + (route != null ? route : request.action))
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, request.method)
                .setAttribute(UrlAttributes.URL_PATH, request.path)
                .setAttribute(UrlAttributes.URL_SCHEME, request.secure ? "https" : "http");
        if (route != null) {
            builder.setAttribute(HttpAttributes.HTTP_ROUTE, route);
        }
        var span = builder.startSpan();
        if (request.remoteAddress != null) {
            span.setAttribute(ClientAttributes.CLIENT_ADDRESS, request.remoteAddress);
        }
        ACTIVE.set(new Active(span, span.makeCurrent()));
    }

    @Override
    public void onInvocationException(Throwable e) {
        var active = ACTIVE.get();
        if (active == null) return;
        active.span().recordException(e);
        active.span().setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
    }

    @Override
    public void onActionInvocationFinally() {
        finish();
    }

    /** Safety net for an invocation that ended before the action hook — nothing must stay current. */
    @Override
    public void invocationFinally() {
        finish();
    }

    private static void finish() {
        var active = ACTIVE.get();
        if (active == null) return;
        ACTIVE.remove();
        active.scope().close();
        var response = Http.Response.current();
        if (response != null && response.status != null) {
            active.span().setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, (long) response.status);
            if (response.status >= 500) {
                active.span().setStatus(StatusCode.ERROR);
            }
        }
        active.span().end();
    }

    private static @Nullable String routePattern(@Nullable String method, @Nullable String action) {
        if (method == null || action == null) return null;
        return ROUTE_PATTERNS.computeIfAbsent(method + " " + action, _ -> {
            for (var route : Router.routes) {
                if (action.equals(route.action) && ("*".equals(route.method) || method.equalsIgnoreCase(route.method))) {
                    return route.path;
                }
            }
            return "";
        }).transform(p -> p.isEmpty() ? null : p);
    }
}
