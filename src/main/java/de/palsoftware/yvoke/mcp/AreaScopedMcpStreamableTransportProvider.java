package de.palsoftware.yvoke.mcp;

import de.palsoftware.yvoke.rag.prompt.Playbook;
import de.palsoftware.yvoke.rag.prompt.PlaybookRepository;
import de.palsoftware.yvoke.shared.security.AreaAccessContext;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Decorates the auto-configured {@link McpStreamableServerTransportProvider} so {@code
 * prompts/list} responses are filtered per caller against {@link AreaAccessContext} and the live
 * {@link PlaybookRepository}.
 *
 * <p>The MCP Java SDK stores registered prompts in one server-wide map and its {@code prompts/list}
 * handler returns every entry regardless of caller identity. Wrapping the session factory lets us
 * intercept only {@link McpSchema.ListPromptsResult} payloads without touching any SDK internals or
 * dropping the {@code prompts} capability that {@code yvoke-desktop} relies on.
 */
public class AreaScopedMcpStreamableTransportProvider
        implements McpStreamableServerTransportProvider {

    private final McpStreamableServerTransportProvider delegate;
    private final PlaybookRepository playbookRepository;

    public AreaScopedMcpStreamableTransportProvider(
            McpStreamableServerTransportProvider delegate, PlaybookRepository playbookRepository) {
        this.delegate = delegate;
        this.playbookRepository = playbookRepository;
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        delegate.setSessionFactory(initRequest -> {
            McpStreamableServerSession.McpStreamableServerSessionInit init =
                    sessionFactory.startSession(initRequest);
            McpStreamableServerSession wrapped =
                    new AreaScopedSession(init.session(), playbookRepository);
            return new McpStreamableServerSession.McpStreamableServerSessionInit(
                    wrapped, init.initResult());
        });
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return delegate.notifyClients(method, params);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    /**
     * Filters a {@link McpSchema.ListPromptsResult} to only prompts that still exist in {@link
     * PlaybookRepository} and belong to an area the given {@link Authentication} may read.
     */
    static McpSchema.ListPromptsResult filterListPromptsResult(
            McpSchema.ListPromptsResult original,
            Authentication auth,
            PlaybookRepository playbookRepository) {
        if (original == null || original.prompts() == null || original.prompts().isEmpty()) {
            return original;
        }
        Map<String, String> areaByName = new HashMap<>();
        for (Playbook pb : playbookRepository.findAll()) {
            if (pb != null && pb.name() != null) {
                areaByName.put(pb.name(), pb.area());
            }
        }
        List<McpSchema.Prompt> allowed =
                original.prompts().stream()
                        .filter(prompt -> {
                            if (prompt == null || prompt.name() == null) {
                                return false;
                            }
                            if (!areaByName.containsKey(prompt.name())) {
                                return false;
                            }
                            return AreaAccessContext.canAccessUserArea(
                                    auth, areaByName.get(prompt.name()));
                        })
                        .toList();
        return new McpSchema.ListPromptsResult(allowed, original.nextCursor(), original.meta());
    }

    private static final class AreaScopedSession extends McpStreamableServerSession {

        private final McpStreamableServerSession delegate;
        private final PlaybookRepository playbookRepository;

        AreaScopedSession(
                McpStreamableServerSession delegate, PlaybookRepository playbookRepository) {
            super(delegate.getId(), null, null, Duration.ofSeconds(1), Map.of(), Map.of());
            this.delegate = delegate;
            this.playbookRepository = playbookRepository;
        }

        @Override
        public void setMinLoggingLevel(McpSchema.LoggingLevel minLoggingLevel) {
            delegate.setMinLoggingLevel(minLoggingLevel);
        }

        @Override
        public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel loggingLevel) {
            return delegate.isNotificationForLevelAllowed(loggingLevel);
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
            return delegate.sendRequest(method, requestParams, typeRef);
        }

        @Override
        public Mono<Void> sendNotification(String method, Object params) {
            return delegate.sendNotification(method, params);
        }

        @Override
        public Mono<Void> delete() {
            return delegate.delete();
        }

        @Override
        public McpStreamableServerSessionStream listeningStream(
                McpStreamableServerTransport transport) {
            return delegate.listeningStream(transport);
        }

        @Override
        public Flux<McpSchema.JSONRPCMessage> replay(Object lastEventId) {
            return delegate.replay(lastEventId);
        }

        @Override
        public Mono<Void> responseStream(
                McpSchema.JSONRPCRequest request, McpStreamableServerTransport transport) {
            if (request != null && McpSchema.METHOD_PROMPT_LIST.equals(request.method())) {
                Authentication captured = SecurityContextHolder.getContext().getAuthentication();
                return delegate.responseStream(
                        request,
                        new PromptsFilteringTransport(transport, captured, playbookRepository));
            }
            return delegate.responseStream(request, transport);
        }

        @Override
        public Mono<Void> accept(McpSchema.JSONRPCNotification notification) {
            return delegate.accept(notification);
        }

        @Override
        public Mono<Void> accept(McpSchema.JSONRPCResponse response) {
            return delegate.accept(response);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class PromptsFilteringTransport implements McpStreamableServerTransport {

        private final McpStreamableServerTransport delegate;
        private final Authentication capturedAuth;
        private final PlaybookRepository playbookRepository;

        PromptsFilteringTransport(
                McpStreamableServerTransport delegate,
                Authentication capturedAuth,
                PlaybookRepository playbookRepository) {
            this.delegate = delegate;
            this.capturedAuth = capturedAuth;
            this.playbookRepository = playbookRepository;
        }

        private McpSchema.JSONRPCMessage rewrite(McpSchema.JSONRPCMessage message) {
            if (message instanceof McpSchema.JSONRPCResponse response
                    && response.result() instanceof McpSchema.ListPromptsResult listResult) {
                Authentication auth =
                        capturedAuth != null
                                ? capturedAuth
                                : SecurityContextHolder.getContext().getAuthentication();
                McpSchema.ListPromptsResult scoped =
                        filterListPromptsResult(listResult, auth, playbookRepository);
                return new McpSchema.JSONRPCResponse(
                        response.jsonrpc(), response.id(), scoped, response.error());
            }
            return message;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return delegate.sendMessage(rewrite(message));
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
            return delegate.sendMessage(rewrite(message), messageId);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(data, typeRef);
        }

        @Override
        public List<String> protocolVersions() {
            return delegate.protocolVersions();
        }
    }
}
