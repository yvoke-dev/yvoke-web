package de.palsoftware.yvoke.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import de.palsoftware.yvoke.chat.core.model.ConversationSetting;
import de.palsoftware.yvoke.chat.core.model.Message;
import de.palsoftware.yvoke.chat.core.service.ChatConversationService;
import de.palsoftware.yvoke.chat.core.repository.MessageRepository;
import de.palsoftware.yvoke.llm.core.model.LlmMessage;
import de.palsoftware.yvoke.llm.core.model.LlmRequest;
import de.palsoftware.yvoke.llm.core.model.LlmResponseChunk;
import de.palsoftware.yvoke.llm.core.model.LlmUsage;
import de.palsoftware.yvoke.llm.core.service.LlmClient;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import de.palsoftware.yvoke.shared.web.ChatEnabledInterceptor;
import de.palsoftware.yvoke.shared.web.RateLimitInterceptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import de.palsoftware.yvoke.shared.web.GenerationRateLimiter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class ChatAsyncControllerIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private PlaybookService playbookService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean(name = "llmProviderClient")
    private LlmClient llmClient;

    private MockMvc mockMvc;

    private List<UUID> conversationsToDelete = new ArrayList<>();

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        conversationsToDelete = new ArrayList<>();
    }

    @AfterEach
    public void tearDown() {
        SecurityContextHolder.clearContext();
        for (UUID conversationId : conversationsToDelete) {
            try {
                chatConversationService.deleteConversation(conversationId);
            } catch (Exception e) {
                // Ignore
            }
        }
        try {
            playbookService.deletePlaybook("correct-playbook");
        } catch (Exception e) {}
    }

    private void setSecurityContext(String oid, String email, String name) {
        OidcUser oidcUser = mock(OidcUser.class);
        when(oidcUser.getClaimAsString("oid")).thenReturn(oid);
        when(oidcUser.getClaimAsString("name")).thenReturn(name);
        when(oidcUser.getClaimAsString("email")).thenReturn(email);

        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(oidcUser);
        
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        when(auth.getAuthorities()).thenAnswer(inv -> authorities);

        SecurityContext secContext = SecurityContextHolder.createEmptyContext();
        secContext.setAuthentication(auth);
        SecurityContextHolder.setContext(secContext);
    }

    private static OidcLoginRequestPostProcessor testUserLogin(String oid, String email, String name) {
        return oidcLogin()
            .idToken(token -> token.claim("oid", oid).claim("name", name).claim("email", email))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    public void testSendAsyncHappyPath() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        // Configure standard settings if needed
        Map<String, Object> settings = new HashMap<>(conv.settings());
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        chatConversationService.updateSettings(conv.id(), settings);

        playbookService.savePlaybook("correct-playbook", "Correct Playbook", "Desc", "Template", List.of(), false);

        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch releaseCall = new CountDownLatch(1);

        doAnswer(inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            callStarted.countDown();
            try {
                releaseCall.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cb.accept(new LlmResponseChunk("Hello async response!", null, null, new LlmUsage(10, 10, 20, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        MvcResult postResult = mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(login)
                .with(csrf())
                .param("content", "Hello async query")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.assistantMessageId").exists())
            .andReturn();

        String responseBody = postResult.getResponse().getContentAsString();
        Map<String, String> responseMap = objectMapper.readValue(responseBody, Map.class);
        UUID assistantMessageId = UUID.fromString(responseMap.get("assistantMessageId"));

        boolean started = callStarted.await(5, TimeUnit.SECONDS);
        assertThat(started).isTrue();

        mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + assistantMessageId + "/status")
                .with(login))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("generating"));

        releaseCall.countDown();

        boolean done = false;
        for (int i = 0; i < 50; i++) {
            MvcResult statusResult = mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + assistantMessageId + "/status")
                    .with(login))
                .andExpect(status().isOk())
                .andReturn();
            String statusBody = statusResult.getResponse().getContentAsString();
            Map<String, Object> statusMap = objectMapper.readValue(statusBody, Map.class);
            if ("done".equals(statusMap.get("status"))) {
                done = true;
                Map<String, Object> messageMap = (Map<String, Object>) statusMap.get("message");
                assertThat(messageMap.get("content")).isEqualTo("Hello async response!");
                break;
            }
            Thread.sleep(100);
        }
        assertThat(done).isTrue();
    }

    /**
     * Documents a WART, deliberately: omitting {@code content} entirely is answered with 500
     * {@code {"error":"Internal server error"}}, not with the 400 the framework would produce on its
     * own.
     *
     * <p>
     * {@code ApiExceptionHandler} must declare a catch-all {@code @ExceptionHandler(Exception.class)}
     * for SEC-17 (no raw exception detail may reach an API caller), and an {@code @ExceptionHandler}
     * match is resolved by {@code ExceptionHandlerExceptionResolver}, which runs AHEAD of
     * {@code DefaultHandlerExceptionResolver}. So the catch-all claims
     * {@code MissingServletRequestParameterException} before Spring's built-in 400 mapping ever gets
     * a turn. That is precisely the mechanism the class documents at ApiExceptionHandler:41-44 as the
     * reason {@code MethodArgumentNotValidException} needed its own branch; the same reasoning was
     * never applied to a missing request PARAMETER.
     *
     * <p>
     * Why it matters to a caller: 400 says "fix your request and it will work", 500 says "the server
     * failed", and 500 is the one a desktop sync client or a script retries with backoff — so a
     * caller that forgot a parameter hammers an endpoint that can never succeed while the operator
     * watches server errors accumulate for a request that was never the server's fault. It also makes
     * the controller's own blank-content branch (ChatAsyncController:37-39, a real 400) unreachable
     * for the missing-parameter case, so the endpoint has two different answers for "no content"
     * depending on whether the parameter was empty or absent.
     *
     * <p>
     * This test pins the CURRENT behaviour so that fixing it is a deliberate act (add
     * {@code @ExceptionHandler(MissingServletRequestParameterException.class)} to
     * {@code ApiExceptionHandler} and change this assertion in the same commit) rather than
     * something that drifts either way unnoticed. The body equality is the SEC-17 half and must stay
     * whatever the status becomes: the exception message names the missing parameter and its type,
     * and nothing generated from an exception may be echoed to an API caller.
     */
    @Test
    public void aMissingContentParameterIsAFiveHundredNotAFourHundred() throws Exception {
        String userOid = "user-a-async-test-oid";
        userRepository.upsert(userOid, "user-a-async@local", "User A");

        // A distinct "sub" so this request draws on its own rate-limit bucket: /chat/*/send-async is
        // limited at 20/min per "user:" + auth.getName(), and every other test in this shared context
        // authenticates as the default oidcLogin() subject.
        var login = oidcLogin()
            .idToken(token -> token.subject("missing-content-probe").claim("oid", userOid)
                .claim("name", "User A").claim("email", "user-a-async@local"))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));

        // No conversation is needed: @RequestParam resolution fails before the handler body runs.
        String body = mockMvc
            .perform(post("/chat/" + UUID.randomUUID() + "/send-async").with(login).with(csrf())
                .param("promptName", "correct-playbook"))
            .andExpect(status().isInternalServerError()).andReturn().getResponse()
            .getContentAsString();

        assertThat(body).as("SEC-17: the missing parameter's name and type must not be echoed back")
            .isEqualTo("{\"error\":\"Internal server error\"}");
    }

    /**
     * An over-limit generation must be refused BEFORE the SSE response is committed, and it must
     * be refused out of the caller's OWN bucket. Two rules, one request.
     *
     * <p>
     * <b>The ordering.</b> {@code RateLimitInterceptor} runs in {@code preHandle}, so an over-limit
     * send is a clean 429 with a JSON body a client can retry. Move the check into the handler (the
     * natural edit when someone wants the limiter to see the resolved conversation, or to exempt a
     * conversation the user owns) and {@code ChatSseController} has already returned an
     * {@code SseEmitter}: the response is committed as {@code 200 text/event-stream}, the user's
     * question has already been persisted by {@code prepare()}, and the browser gets a truncated
     * answer instead of a retryable status. {@code RateLimitInterceptorTest} calls
     * {@code preHandle} by hand, so it proves nothing about WHEN the container calls it, and
     * {@code onlyTheTwoSendRoutesCarryTheRateLimiterWhileEveryChatRouteCarriesTheChatGate} pins
     * only the route list — an interceptor mapped to the right route but consulted too late throws
     * nothing, logs nothing and alters no response.
     *
     * <p>
     * <b>The bucket key.</b> SEC-03 partitions on {@code "user:" + auth.getName()}, which for an
     * OIDC session is the {@code sub} claim — NOT the {@code oid} this application uses everywhere
     * else as its user identity. They are two different keys for the same person and nothing pinned
     * which one is used, so re-keying the limiter (to the remote address, to the oid, to the email)
     * silently repartitions every bucket: either one shared bucket 429s unrelated users, or every
     * request gets a fresh bucket and the limit stops existing. This test drains the bucket named by
     * the {@code sub} it then authenticates with, so the 429 can only arrive if that is the key.
     *
     * <p>
     * A distinct {@code sub} is used so this test draws on a bucket of its own — every other test in
     * this shared context authenticates as the default {@code oidcLogin()} subject, and draining
     * that one would 429 them. The drain assertions are the non-vacuity guard: with rate limiting
     * disabled the loop would never empty the bucket and everything below would prove nothing.
     */
    @Test
    public void anOverLimitSendIsRejectedBeforeTheSseStreamCommitsAndPersistsNothing()
        throws Exception {
        String userOid = "rate-limit-probe-oid";
        String subject = "rate-limit-probe-sub";
        userRepository.upsert(userOid, "rate-limit-probe@local", "Rate Limit Probe");
        setSecurityContext(userOid, "rate-limit-probe@local", "Rate Limit Probe");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        GenerationRateLimiter limiter = context.getBean(GenerationRateLimiter.class);
        int capacity =
            context.getEnvironment().getRequiredProperty("app.rate-limit.capacity", Integer.class);
        String bucket = "user:" + subject;
        for (int i = 0; i < capacity; i++) {
            assertThat(limiter.tryAcquire(bucket))
                .as("draining token %d of the configured capacity", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire(bucket))
            .as("the bucket keyed by the OIDC sub must really be empty, or the 429 below proves "
                + "nothing")
            .isFalse();

        var login = oidcLogin()
            .idToken(token -> token.subject(subject).claim("oid", userOid)
                .claim("name", "Rate Limit Probe").claim("email", "rate-limit-probe@local"))
            .authorities(new SimpleGrantedAuthority("ROLE_USER"));

        MvcResult result = mockMvc
            .perform(post("/chat/" + conv.id() + "/send").with(login).with(csrf()).param("content",
                "this question must never reach the model"))
            .andExpect(status().isTooManyRequests()).andExpect(request().asyncNotStarted())
            .andReturn();

        assertThat(result.getResponse().getContentType())
            .as("the refusal must not be the streaming response the handler would have opened")
            .doesNotContain("text/event-stream");
        assertThat(result.getResponse().getContentAsString())
            .as("a retryable 429 the client can act on, not a stream that dies mid-answer")
            .contains("Rate limit exceeded");
        assertThat(messageRepository.countByConversationId(conv.id()))
            .as("preHandle runs before the controller, so the question is never persisted either")
            .isZero();
    }

    @Test
    public void testSendAsyncInvalidPlaybook() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(login)
                .with(csrf())
                .param("content", "Hello")
                .param("promptName", "non-existent-playbook"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The selected playbook is invalid."));
    }

    @Test
    public void testSendAsyncAccessDenied() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("correct-playbook", "Correct Playbook", "Desc", "Template", List.of(), false);

        String userBOid = "user-b-async-test-oid";
        userRepository.upsert(userBOid, "user-b-async@local", "User B");
        var loginB = testUserLogin(userBOid, "user-b-async@local", "User B");

        // User B cannot send message to User A's conversation
        mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(loginB)
                .with(csrf())
                .param("content", "Hello")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isForbidden());

        // User B cannot poll message status on User A's conversation
        mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + UUID.randomUUID() + "/status")
                .with(loginB))
            .andExpect(status().isForbidden());
    }

    /**
     * Every other POST in this suite carries {@code .with(csrf())}, so the whole file would stay
     * green if CSRF protection were switched off for {@code /chat/**} — the token would simply be
     * ignored. Nothing else pins it: the browser chain's CSRF configuration has no unit test, and
     * the e2e suite drives a real browser that always sends the token. The exposure is concrete
     * rather than theoretical, because {@code /chat/{id}/send-async} is a state-changing,
     * cookie-authenticated, LLM-spending endpoint whose id is a plain path variable — an attacker
     * page could post into a signed-in user's conversation, injecting content into a thread the user
     * believes is theirs and burning their quota, and (unlike the SSE endpoint) it needs no response
     * to be read back to do damage.
     *
     * <p>The two halves must be the SAME request but for the token. Asserting only the 403 would
     * pass against a chain that rejects the request for some unrelated reason — a wrong playbook
     * name, an unowned conversation — so the accepted half is what proves the token is the only
     * difference.
     */
    @Test
    public void aSendAsyncWithoutACsrfTokenIsRejectedWhileTheIdenticalRequestWithOneIsAccepted()
        throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());
        playbookService.savePlaybook("correct-playbook", "Correct Playbook", "Desc", "Template",
            List.of(), false);

        doAnswer(inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("ok", null, null, new LlmUsage(1, 1, 2, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        mockMvc
            .perform(post("/chat/" + conv.id() + "/send-async").with(login)
                .param("content", "Hello async query").param("promptName", "correct-playbook"))
            .andExpect(status().isForbidden());

        mockMvc
            .perform(post("/chat/" + conv.id() + "/send-async").with(login).with(csrf())
                .param("content", "Hello async query").param("promptName", "correct-playbook"))
            .andExpect(status().isAccepted());
    }

    /**
     * The rate limiter's path list is a two-sided decision and each side fails in the opposite
     * direction, silently.
     *
     * <p>
     * Too narrow: {@code /chat/{id}/send} and {@code /chat/{id}/send-async} are two doors into the
     * SAME generation path — the SSE one and the poll-based one — and the browser picks between them
     * at runtime. Dropping either pattern leaves that door completely unlimited while the other
     * still looks guarded, so SEC-03 appears intact in review and in any manual test that happens to
     * use the other mode, and one principal can spend unbounded LLM budget through the unguarded
     * one.
     *
     * <p>
     * Too wide: {@code /chat/{id}/messages/{messageId}/status} is polled every three seconds for the
     * whole duration of a generation, and {@code /chat/{id}/stop} is how a user cancels one. At the
     * configured 20 requests per 60 seconds, widening the pattern to {@code /chat/**} — the obvious
     * "just cover the whole chat area" edit — exhausts a user's bucket in about a minute of watching
     * their own answer arrive, and then 429s the poll and, worse, the Stop button: the user cannot
     * cancel the generation that is burning their quota. That is why the two cheap GET/stop routes
     * are deliberately excluded rather than accidentally missed.
     *
     * <p>
     * Nothing else pins this. {@code RateLimitInterceptorTest} drives the interceptor directly with
     * a hand-built request, so it is green whatever routes it is mapped to; and an interceptor that
     * is simply never invoked throws nothing, logs nothing and alters no response — the only
     * evidence is a 429 that never arrives. The chat-gate assertions are the control: they prove the
     * probe requests really do resolve to chat handlers with interceptors attached, so a
     * {@code doesNotHaveAnyElementsOfTypes} above cannot pass merely because the chain came back
     * empty.
     *
     * <p>
     * The chain is resolved through {@code RequestMappingHandlerMapping} rather than by performing
     * the requests, because performing them would consume rate-limit tokens and start a real
     * generation — the measurement would perturb the thing being measured.
     */
    @Test
    public void onlyTheTwoSendRoutesCarryTheRateLimiterWhileEveryChatRouteCarriesTheChatGate()
        throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        List<HandlerInterceptor> send =
            interceptorsFor("POST", "/chat/" + conversationId + "/send");
        List<HandlerInterceptor> sendAsync =
            interceptorsFor("POST", "/chat/" + conversationId + "/send-async");
        List<HandlerInterceptor> statusPoll = interceptorsFor("GET",
            "/chat/" + conversationId + "/messages/" + messageId + "/status");
        List<HandlerInterceptor> stop =
            interceptorsFor("POST", "/chat/" + conversationId + "/stop");

        assertThat(send).as("the SSE generation entry point must be rate limited")
            .hasAtLeastOneElementOfType(RateLimitInterceptor.class);
        assertThat(sendAsync).as("the async generation entry point must be rate limited too")
            .hasAtLeastOneElementOfType(RateLimitInterceptor.class);
        assertThat(statusPoll)
            .as("the 3-second status poll must NOT be limited; it would 429 mid-answer")
            .doesNotHaveAnyElementsOfTypes(RateLimitInterceptor.class);
        assertThat(stop).as("Stop must never be rate limited; it is the way out of a runaway run")
            .doesNotHaveAnyElementsOfTypes(RateLimitInterceptor.class);

        assertThat(send).hasAtLeastOneElementOfType(ChatEnabledInterceptor.class);
        assertThat(sendAsync).hasAtLeastOneElementOfType(ChatEnabledInterceptor.class);
        assertThat(statusPoll).hasAtLeastOneElementOfType(ChatEnabledInterceptor.class);
        assertThat(stop).as("app.chat.enabled=false must switch off every chat route, not just sends")
            .hasAtLeastOneElementOfType(ChatEnabledInterceptor.class);
    }

    /**
     * Resolves the handler execution chain for a route without executing it. A
     * {@link org.springframework.web.servlet.handler.MappedInterceptor} whose patterns match is
     * unwrapped by {@code AbstractHandlerMapping}, so the chain holds the raw interceptor instances.
     * The parsed request path has to be cached explicitly: outside a real dispatch nothing has done
     * it, and the mapping requires it when {@code PathPatternParser} is in use.
     */
    private List<HandlerInterceptor> interceptorsFor(String method, String uri) throws Exception {
        RequestMappingHandlerMapping mapping =
            context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        ServletRequestPathUtils.parseAndCache(request);

        HandlerExecutionChain chain = mapping.getHandler(request);
        assertThat(chain).as("%s %s must resolve to a handler", method, uri).isNotNull();
        return chain.getInterceptorList();
    }

    @Test
    public void testStopCancelsAsyncGeneration() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("correct-playbook", "Correct Playbook", "Desc", "Template", List.of(), false);

        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch cancellationHandled = new CountDownLatch(1);

        doAnswer(inv -> {
            callStarted.countDown();
            try {
                // Sleep longer to ensure cancellation is requested from test thread
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                cancellationHandled.countDown();
                Thread.currentThread().interrupt();
                throw new CancellationException("Chat generation cancelled");
            }
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        MvcResult postResult = mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(login)
                .with(csrf())
                .param("content", "Hello stop query")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.assistantMessageId").exists())
            .andReturn();

        String responseBody = postResult.getResponse().getContentAsString();
        Map<String, String> responseMap = objectMapper.readValue(responseBody, Map.class);
        UUID assistantMessageId = UUID.fromString(responseMap.get("assistantMessageId"));

        boolean started = callStarted.await(5, TimeUnit.SECONDS);
        assertThat(started).isTrue();

        // Call stop endpoint
        mockMvc.perform(post("/chat/" + conv.id() + "/stop")
                .with(login)
                .with(csrf()))
            .andExpect(status().isOk());

        boolean cancelled = cancellationHandled.await(5, TimeUnit.SECONDS);
        assertThat(cancelled).isTrue();

        // Poll until it transitions to 'cancelled' — NOT 'error'. A stop and a failure both used to
        // land on 'error', which left the browser unable to tell them apart and captioning genuine
        // failures as "[Generation stopped by user]".
        boolean cancelledStatus = false;
        for (int i = 0; i < 50; i++) {
            MvcResult statusResult = mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + assistantMessageId + "/status")
                    .with(login))
                .andExpect(status().isOk())
                .andReturn();
            String statusBody = statusResult.getResponse().getContentAsString();
            Map<String, Object> statusMap = objectMapper.readValue(statusBody, Map.class);
            if ("cancelled".equals(statusMap.get("status"))) {
                cancelledStatus = true;
                Map<String, Object> messageMap = (Map<String, Object>) statusMap.get("message");
                assertThat((String) messageMap.get("content")).contains("[Generation stopped by user]");
                break;
            }
            Thread.sleep(100);
        }
        assertThat(cancelledStatus).isTrue();
    }

    /**
     * The mirror of the stop test: a provider failure must report 'error' and must NOT carry the
     * cancellation wording. Together the two pin that the two outcomes stay distinguishable.
     */
    @Test
    public void testProviderFailureReportsErrorAndNotCancelled() throws Exception {
        String userOid = "user-fail-async-test-oid";
        userRepository.upsert(userOid, "user-fail-async@local", "User Fail");
        setSecurityContext(userOid, "user-fail-async@local", "User Fail");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("failure-playbook", "Failure Playbook", "Desc", "Template",
            List.of(), false);

        doAnswer(inv -> {
            throw new IllegalStateException("simulated provider fault");
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userOid, "user-fail-async@local", "User Fail");

        MvcResult result = mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(login)
                .with(csrf())
                .param("content", "trigger a failure")
                .param("promptName", "failure-playbook"))
            .andExpect(status().isAccepted())
            .andReturn();

        Map<String, String> responseMap =
            objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        UUID assistantMessageId = UUID.fromString(responseMap.get("assistantMessageId"));

        boolean errored = false;
        for (int i = 0; i < 50; i++) {
            MvcResult statusResult = mockMvc.perform(get("/chat/" + conv.id() + "/messages/" + assistantMessageId + "/status")
                    .with(login))
                .andExpect(status().isOk())
                .andReturn();
            Map<String, Object> statusMap =
                objectMapper.readValue(statusResult.getResponse().getContentAsString(), Map.class);
            if ("error".equals(statusMap.get("status"))) {
                errored = true;
                Map<String, Object> messageMap = (Map<String, Object>) statusMap.get("message");
                String content = (String) messageMap.get("content");
                assertThat(content).contains("could not complete this response");
                assertThat(content).doesNotContain("stopped by user");
                assertThat(content).doesNotContain("simulated provider fault");
                break;
            }
            Thread.sleep(100);
        }
        assertThat(errored).isTrue();
    }

    /**
     * The wire format of {@code Message.createdAt}, which is a real contract and is decided by a
     * framework default that no line of this project states.
     *
     * <p>
     * There are two Jackson mappers in this application and they are not the same library. The
     * {@code ObjectMapper} bean in {@code RestClientConfig} is Jackson 2
     * ({@code com.fasterxml.jackson.databind}) and is only used where code injects it explicitly —
     * repositories, SDK plumbing, this test. Everything that goes out over MVC is written by the
     * Jackson 3 ({@code tools.jackson}) mapper that Boot 4 builds, and {@code DateTimeFeature
     * .WRITE_DATES_AS_TIMESTAMPS} is disabled by default there — so an {@code Instant} serializes as
     * an ISO-8601 string. Nothing in {@code application.yml} says so: it carries no
     * {@code spring.jackson} block at all.
     *
     * <p>
     * Anyone adding a {@code spring.jackson.datatype.datetime.write-dates-as-timestamps} key, a
     * mapper-builder customizer, or an {@code @Primary} mapper of their own — all of which are
     * routine and none of which mention this endpoint — flips every timestamp in every JSON response
     * from {@code "2026-08-08T09:12:33.481Z"} to {@code 1786...}. That is a cross-repository break:
     * the same serialized {@code Message} shape is what the desktop client receives from
     * {@code /api/chat/v1/conversations/{id}/messages}, and a client parsing a date string gets a
     * hard failure while one doing arithmetic on it silently lands in 1970.
     *
     * <p>
     * Nothing in this repository asserts it. {@code thread.js} never reads the field, so no browser
     * test can notice; the ITs that already read this endpoint's body pull out {@code status} and
     * {@code content} only; and the change produces no compile error and no failing assertion
     * anywhere — the JSON is still valid JSON.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void theStatusPollRendersCreatedAtAsAnIsoStringNotAnEpochNumber() throws Exception {
        String userOid = "user-a-async-test-oid";
        userRepository.upsert(userOid, "user-a-async@local", "User A");
        setSecurityContext(userOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        // A terminal message, so the poll returns the serialized Message and not just a status.
        UUID doneMessageId = UUID.randomUUID();
        messageRepository.save(new Message(doneMessageId, conv.id(), "assistant", "An answer.",
            null, List.of(), List.of(), Instant.now(), null, null, null, null, null, "done"));

        var login = testUserLogin(userOid, "user-a-async@local", "User A");

        String body = mockMvc
            .perform(
                get("/chat/" + conv.id() + "/messages/" + doneMessageId + "/status").with(login))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        Map<String, Object> statusMap = objectMapper.readValue(body, Map.class);
        assertThat(statusMap.get("status")).isEqualTo("done");

        Map<String, Object> messageMap = (Map<String, Object>) statusMap.get("message");
        Object createdAt = messageMap.get("createdAt");

        assertThat(createdAt)
            .as("createdAt must be an ISO-8601 STRING; a number means WRITE_DATES_AS_TIMESTAMPS "
                + "was re-enabled and every client parsing this field breaks. Body: %s", body)
            .isInstanceOf(String.class);
        assertThat(Instant.parse((String) createdAt))
            .as("and it must round-trip back to the instant it represents").isNotNull();
        assertThat(body).as("no epoch number may stand where the timestamp belongs")
            .doesNotContain("\"createdAt\":1").doesNotContain("\"createdAt\":[");
    }

    @Test
    public void testThreadViewShowsGeneratingMessage() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        // Insert assistant message with status generating
        UUID generatingMsgId = UUID.randomUUID();
        Message generatingMsg = new Message(
            generatingMsgId,
            conv.id(),
            "assistant",
            "",
            null,
            List.of(),
            List.of(),
            Instant.now(),
            null, null, null, null, null,
            "generating"
        );
        messageRepository.save(generatingMsg);

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        MvcResult viewResult = mockMvc.perform(get("/chat/" + conv.id())
                .with(login))
            .andExpect(status().isOk())
            .andReturn();

        String htmlContent = viewResult.getResponse().getContentAsString();
        assertThat(htmlContent).contains("sync-loader-container");
        assertThat(htmlContent).contains("sync-loader-text");
        assertThat(htmlContent).contains("Thinking");
        assertThat(htmlContent).contains("message-assistant sync-loading");
    }

    @Test
    public void testGeneratingMessageExcludedFromHistory() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        playbookService.savePlaybook("correct-playbook", "Correct Playbook Title", "Correct Playbook Desc", "template", List.of(), false);

        // 1. Insert a user message
        UUID userMsgId = UUID.randomUUID();
        Message userMsg = new Message(
            userMsgId,
            conv.id(),
            "user",
            "First question",
            "correct-playbook",
            List.of(),
            List.of(),
            Instant.now(),
            null, null, null, null, null,
            "done"
        );
        messageRepository.save(userMsg);

        // 2. Insert a generating assistant message
        UUID generatingMsgId = UUID.randomUUID();
        Message generatingMsg = new Message(
            generatingMsgId,
            conv.id(),
            "assistant",
            "Thinking...",
            null,
            List.of(),
            List.of(),
            Instant.now(),
            null, null, null, null, null,
            "generating"
        );
        messageRepository.save(generatingMsg);

        // 3. Set up llmClient generateStream mock to capture requests
        CopyOnWriteArrayList<LlmRequest> capturedRequests = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            LlmRequest req = inv.getArgument(0);
            capturedRequests.add(req);
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("Hello again!", null, null, new LlmUsage(10, 10, 20, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        // 4. Send another message async
        mockMvc.perform(post("/chat/" + conv.id() + "/send-async")
                .with(login)
                .with(csrf())
                .param("content", "Second question")
                .param("promptName", "correct-playbook"))
            .andExpect(status().isAccepted());

        boolean captured = false;
        for (int i = 0; i < 50; i++) {
            if (!capturedRequests.isEmpty()) {
                captured = true;
                break;
            }
            Thread.sleep(100);
        }
        assertThat(captured).isTrue();

        LlmRequest request = capturedRequests.get(0);
        List<LlmMessage> messages = request.messages();

        // Check that generating message is excluded
        boolean containsGenerating = messages.stream()
            .anyMatch(m -> "Thinking...".equals(m.content()));
        assertThat(containsGenerating).isFalse();
    }

    @Test
    public void testStreamingModeUnaffected() throws Exception {
        String userAOid = "user-a-async-test-oid";
        userRepository.upsert(userAOid, "user-a-async@local", "User A");
        setSecurityContext(userAOid, "user-a-async@local", "User A");

        Conversation conv = chatConversationService.createConversation();
        conversationsToDelete.add(conv.id());

        // Configure standard settings if needed
        Map<String, Object> settings = new HashMap<>(conv.settings());
        settings.put(ConversationSetting.MODEL.getValue(), "gemini-3.1-flash-lite");
        chatConversationService.updateSettings(conv.id(), settings);

        playbookService.savePlaybook("correct-playbook", "Correct Playbook", "Desc", "Template", List.of(), false);

        doAnswer(inv -> {
            Consumer<LlmResponseChunk> cb = inv.getArgument(1);
            cb.accept(new LlmResponseChunk("SSE chunk response!", null, null, new LlmUsage(5, 5, 10, 0, 0)));
            return null;
        }).when(llmClient).generateStream(any(LlmRequest.class), any());

        var login = testUserLogin(userAOid, "user-a-async@local", "User A");

        MvcResult sseResult = mockMvc.perform(post("/chat/" + conv.id() + "/send")
                .with(login)
                .with(csrf())
                .param("content", "Hello SSE query")
                .param("promptName", "correct-playbook"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(sseResult))
            .andExpect(status().isOk());

        String sseResponse = sseResult.getResponse().getContentAsString();
        String cleaned = sseResponse.replace("data:", "").replace("\n", "").replace("\r", "").trim();
        assertThat(cleaned).contains("SSE chunk response!");

        // Wait a bit for status to transition to done in db
        boolean saved = false;
        for (int i = 0; i < 50; i++) {
            List<Message> dbMsgs = messageRepository.findByConversationId(conv.id(), 100, 0);
            boolean hasAssistantDone = dbMsgs.stream()
                .anyMatch(m -> "assistant".equals(m.role()) && "done".equals(m.status()) && m.content().contains("SSE chunk response!"));
            if (hasAssistantDone) {
                saved = true;
                break;
            }
            Thread.sleep(100);
        }
        assertThat(saved).isTrue();
    }
}
