package com.inqwise.context;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import com.inqwise.context.ContextPropagationException.Direction;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.internal.http.HttpServerRequestInternal;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientSession;
import io.vertx.ext.web.client.impl.HttpContext;

/**
 * Opt-in HTTP request/response metadata propagation. Applications using this
 * adapter must include the Vert.x WebClient dependency. Client
 * destinations are exact origins, not wildcard hosts or path prefixes.
 */
public final class HttpContextTransport {
    private static final String STATE = HttpContextTransport.class.getName() + ".state";
    private static final Map<WebClient, Boolean> installed = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ContextTransportEngine engine = new ContextTransportEngine(ContextChainStorageHolder::getDataProviders);

    private HttpContextTransport() {
    }

    private record Origin(String scheme, String host, int port) {
        static Origin from(URI uri) {
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("Expected an HTTP or HTTPS origin");
            }
            String normalized = scheme.toLowerCase(Locale.ROOT);
            return new Origin(normalized, HttpContextTransport.host(uri.getHost()),
                uri.getPort() == -1 ? (normalized.equals("https") ? 443 : 80) : uri.getPort());
        }
    }

    private static final class RequestState {
        final ContextInternal caller;
        boolean allowed;
        // Redirect options can drop per-request proxy settings; keep denial latched.
        boolean proxyConfigured;

        RequestState(ContextInternal caller, boolean proxyConfigured) {
            this.caller = caller;
            this.proxyConfigured = proxyConfigured;
        }
    }

    /**
     * Installs client propagation using the shared default configuration.
     *
     * @param client the shared client, installed at most once
     * @param allowedOrigins exact origins such as {@code https://service.example:8443}
     * @return the same client
     * @throws IllegalArgumentException if an origin is invalid or the client is already installed
     * @throws IllegalStateException if default configuration cannot resolve
     * @throws NullPointerException if an argument is null
     */
    public static WebClient install(WebClient client, URI... allowedOrigins) {
        return install(client, null, allowedOrigins);
    }

    /**
     * Installs a client adapter with explicit configuration. Each send and
     * redirect is checked against the allowlist. Managed headers are cleared
     * before writing; responses from denied origins are not imported. No origins
     * means no propagation. Requests must be sent from an active Vert.x context.
     * Client-default and per-request proxies disable metadata propagation, not
     * native payload delivery. Client proxy defaults are captured at installation.
     * Proxy denial persists for the whole request, including redirects with
     * regenerated options that no longer specify a proxy.
     * Create session wrappers and add interceptors that mutate headers or routing
     * before installing on the final client. A session derived from an already
     * instrumented plain client fails before sending rather than inheriting
     * unsafe interceptor ordering.
     *
     * @param client the shared client, installed at most once
     * @param configuration the explicit configuration, or null for the shared default
     * @param allowedOrigins exact HTTP(S) origins without credentials, paths, queries, or fragments
     * @return the same client
     * @throws IllegalArgumentException if configuration, an origin, or installation is invalid
     * @throws IllegalStateException if default configuration cannot resolve
     * @throws NullPointerException if the client, origins array, or an origin is null
     */
    public static WebClient install(WebClient client, ContextTransportConfiguration configuration, URI... allowedOrigins) {
        Objects.requireNonNull(client, "Client is mandatory");
        Objects.requireNonNull(allowedOrigins, "Origins are mandatory");
        ContextTransportConfiguration selected = validated(configuration);
        Set<Origin> origins = new HashSet<>();
        for (URI uri : allowedOrigins) {
            Objects.requireNonNull(uri, "Origin is mandatory");
            Origin origin = Origin.from(uri);
            if (!(uri.getPath().isEmpty() || uri.getPath().equals("/")) || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Allowed origin must not contain a path, query, or fragment");
            }
            origins.add(origin);
        }
        Set<Origin> captured = Set.copyOf(origins);
        boolean installedOnSession = client instanceof WebClientSession;
        synchronized (installed) {
            if (installed.containsKey(client)) {
                throw new IllegalArgumentException("HTTP context transport is already installed on this client");
            }
            // A request builder exposes inherited proxy defaults without dispatching a request.
            boolean defaultProxy = client.get("/").proxy() != null;
            client.addInterceptor(event -> intercept(event, selected, captured, installedOnSession, defaultProxy));
            installed.put(client, Boolean.TRUE);
        }
        return client;
    }

    /**
     * Wraps a core HTTP server handler using the shared default configuration.
     *
     * @param application the application request handler
     * @return a handler using Vert.x's isolated context for each request
     * @throws NullPointerException if the handler is null
     * @throws IllegalStateException if default configuration cannot resolve
     */
    public static Handler<HttpServerRequest> handler(Handler<HttpServerRequest> application) {
        return handler(null, application);
    }

    /**
     * Wraps a server handler with request import and late response export. This
     * adapter owns {@code headersEndHandler}; application code must not replace
     * that hook. Responses carrying metadata are marked {@code Cache-Control:
     * no-store}. Metadata is untrusted input, not authenticated identity.
     * Native requests and standard wrappers must use their associated isolated
     * request context. Other request implementations must preserve this
     * dispatch affinity themselves.
     *
     * @param configuration the explicit configuration, or null for the shared default
     * @param application the application request handler
     * @return the wrapped handler
     * @throws NullPointerException if the handler is null
     * @throws IllegalArgumentException if a selected key cannot be mapped to an HTTP header
     * @throws IllegalStateException if configuration cannot resolve, the context is not isolated,
     *         or a native request's associated context does not match
     */
    public static Handler<HttpServerRequest> handler(ContextTransportConfiguration configuration, Handler<HttpServerRequest> application) {
        Objects.requireNonNull(application, "Request handler is mandatory");
        ContextTransportConfiguration selected = validated(configuration);
        return request -> {
            ContextInternal incoming = currentContext();
            if (!incoming.isDuplicate() || (request instanceof HttpServerRequestInternal nativeRequest
                    && nativeRequest.context() != incoming)) {
                throw new IllegalStateException("HTTP transport requires the isolated context associated with this request");
            }
            engine.read(incoming, new HttpContextCarrier(request.headers()), selected.includedKeys());
            request.response().headersEndHandler(ignored -> {
                try {
                    var headers = request.response().headers();
                    HttpContextCarrier.clear(headers);
                    engine.write(incoming, new HttpContextCarrier(headers), selected.includedKeys());
                    if (HttpContextCarrier.hasMetadata(headers)) {
                        headers.set("Cache-Control", "no-store");
                    }
                } catch (Exception failure) {
                    ContextTransportEngine.reportFailure(incoming, Direction.OUTBOUND, null, failure);
                }
            });
            application.handle(request);
        };
    }

    private static ContextTransportConfiguration validated(ContextTransportConfiguration configuration) {
        ContextTransportConfiguration selected = ContextTransport.resolveConfiguration(configuration);
        Set<String> names = new HashSet<>();
        for (String key : selected.includedKeys()) {
            if (!names.add(HttpContextCarrier.name(key))) {
                throw new IllegalArgumentException("Selected metadata keys conflict in HTTP headers");
            }
        }
        return selected;
    }

    private static void intercept(HttpContext<?> event, ContextTransportConfiguration configuration, Set<Origin> origins,
            boolean installedOnSession, boolean defaultProxy) {
        switch (event.phase()) {
            case PREPARE_REQUEST -> {
                // Sessions copy parent interceptors before adding their own header-merging stage.
                if (!installedOnSession && event.privateCacheStore() != null) {
                    throw new IllegalStateException(
                        "Create the WebClient session before installing HTTP context transport on the final session");
                }
                event.set(STATE, new RequestState(currentContext(), defaultProxy || event.request().proxy() != null));
                event.next();
            }
            case SEND_REQUEST, RECEIVE_RESPONSE -> {
                RequestState state = event.get(STATE);
                state.caller.emit(event, message -> {
                    Direction direction = message.phase() == io.vertx.ext.web.client.impl.ClientPhase.SEND_REQUEST
                        ? Direction.OUTBOUND : Direction.INBOUND;
                    try {
                        if (direction == Direction.OUTBOUND) {
                            state.proxyConfigured |= message.requestOptions().getProxyOptions() != null;
                            state.allowed = allowed(message, origins, state.proxyConfigured);
                            var headers = message.clientRequest().headers();
                            HttpContextCarrier.clear(headers);
                            if (state.allowed) {
                                engine.write(state.caller, new HttpContextCarrier(headers), configuration.includedKeys());
                            }
                        } else if (state.allowed) {
                            engine.read(state.caller, new HttpContextCarrier(message.clientResponse().headers()), configuration.includedKeys());
                        }
                    } catch (Exception failure) {
                        ContextTransportEngine.reportFailure(state.caller, direction, null, failure);
                    } finally {
                        message.next();
                    }
                });
            }
            default -> event.next();
        }
    }

    private static boolean allowed(HttpContext<?> event, Set<Origin> origins, boolean proxyConfigured) {
        try {
            var options = event.requestOptions();
            if (proxyConfigured) {
                return false;
            }
            URI uri = URI.create(event.clientRequest().absoluteURI());
            Origin destination = Origin.from(uri);
            var authority = event.clientRequest().authority();
            // Explicit socket routing or an overridden authority needs a separate trust policy.
            return options.getServer() == null && options.getHost() != null
                && host(options.getHost()).equals(destination.host())
                && (options.getPort() == null || options.getPort() == destination.port())
                && (authority == null || (host(authority.host()).equals(destination.host())
                    && (authority.port() == -1 || authority.port() == destination.port())))
                && matchesHostHeader(event, destination)
                && origins.contains(destination);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean matchesHostHeader(HttpContext<?> event, Origin destination) {
        // The native authority accessor does not reflect manually supplied Host headers.
        var values = event.clientRequest().headers().getAll("Host");
        if (values.isEmpty()) {
            return true;
        }
        if (values.size() != 1) {
            return false;
        }
        HostAndPort authority = HostAndPort.parseAuthority(values.getFirst(), destination.scheme().equals("https") ? 443 : 80);
        return authority != null && host(authority.host()).equals(destination.host())
            && authority.port() == destination.port();
    }

    private static String host(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.startsWith("[") && normalized.endsWith("]")
            ? normalized.substring(1, normalized.length() - 1) : normalized;
    }

    private static ContextInternal currentContext() {
        ContextInternal context = ContextInternal.current();
        if (context == null) {
            throw new IllegalStateException("HTTP context transport requires a calling Vert.x context");
        }
        return context;
    }
}
