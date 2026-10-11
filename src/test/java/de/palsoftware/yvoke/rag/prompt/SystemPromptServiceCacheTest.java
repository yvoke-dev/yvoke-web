package de.palsoftware.yvoke.rag.prompt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.shared.config.CacheConfig;
import de.palsoftware.yvoke.shared.config.repository.AppConfigRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(classes = {SystemPromptService.class, CacheConfig.class,
    SystemPromptServiceCacheTest.TestConfig.class})
@TestPropertySource(properties = {"app.ai.rag.default-prompt-name=default-chat"})
class SystemPromptServiceCacheTest {

    private static final String AREA = "OIM";

    @Configuration
    @EnableCaching
    static class TestConfig {
        @Bean
        SystemPromptRepository systemPromptRepository() {
            return mock(SystemPromptRepository.class);
        }

        @Bean
        AppConfigRepository appConfigRepository() {
            AppConfigRepository mock = mock(AppConfigRepository.class);
            Map<String, String> config = new HashMap<>();
            when(mock.get(any(), any()))
                .thenAnswer(inv -> config.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
            doAnswer(inv -> {
                config.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(mock).set(any(), any());
            return mock;
        }

        @Bean
        AreaService areaService() {
            AreaService mock = mock(AreaService.class);
            when(mock.requireArea(any()))
                .thenAnswer(inv -> inv.getArgument(0) != null ? inv.getArgument(0) : "OIM");
            return mock;
        }
    }

    private static final Map<String, String> appConfigStore = new HashMap<>();

    @Autowired
    private SystemPromptService systemPromptService;

    @Autowired
    private SystemPromptRepository systemPromptRepository;

    @Autowired
    private AppConfigRepository appConfigRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        reset(systemPromptRepository);
        reset(appConfigRepository);
        appConfigStore.clear();
        when(appConfigRepository.get(any(), any()))
            .thenAnswer(inv -> appConfigStore.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        doAnswer(inv -> {
            appConfigStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(appConfigRepository).set(any(), any());

        Cache cache = cacheManager.getCache(CacheConfig.SYSTEM_PROMPTS);
        if (cache != null) {
            cache.clear();
        }
        Cache appCache = cacheManager.getCache(CacheConfig.APP_CONFIG);
        if (appCache != null) {
            appCache.clear();
        }
    }

    @Test
    void getPromptNormalizesWhitespaceKeyAndHitsCache() {
        SystemPrompt prompt =
            new SystemPrompt("test-prompt", SystemPromptType.CHAT, "System body", "Description");
        when(systemPromptRepository.findByName("test-prompt")).thenReturn(Optional.of(prompt));

        // Untrimmed call: fetches from repository and caches under normalized key
        Optional<SystemPrompt> first = systemPromptService.getPrompt("  test-prompt  ");
        assertTrue(first.isPresent());
        verify(systemPromptRepository, times(1)).findByName("test-prompt");

        // Trimmed call: must HIT cache and NOT invoke repository a second time
        Optional<SystemPrompt> second = systemPromptService.getPrompt("test-prompt");
        assertTrue(second.isPresent());
        verify(systemPromptRepository, times(1)).findByName("test-prompt");
    }

    @Test
    void deletePromptWithUntrimmedNameEvictsTrimmedCacheEntry() {
        SystemPrompt prompt =
            new SystemPrompt("test-prompt", SystemPromptType.CHAT, "System body", "Description");
        when(systemPromptRepository.findByName("test-prompt")).thenReturn(Optional.of(prompt));

        // Populate cache via trimmed call
        systemPromptService.getPrompt("test-prompt");
        verify(systemPromptRepository, times(1)).findByName("test-prompt");

        // Delete with untrimmed name
        systemPromptService.deletePrompt("  test-prompt  ");
        verify(systemPromptRepository, times(1)).delete("test-prompt");

        // Subsequent getPrompt with trimmed name must MISS cache and call repository again
        when(systemPromptRepository.findByName("test-prompt")).thenReturn(Optional.empty());
        Optional<SystemPrompt> afterDelete = systemPromptService.getPrompt("test-prompt");
        assertTrue(afterDelete.isEmpty());
        verify(systemPromptRepository, times(2)).findByName("test-prompt");
    }

    @Test
    void deletePromptWithTrimmedNameEvictsUntrimmedCachedEntry() {
        SystemPrompt prompt =
            new SystemPrompt("test-prompt", SystemPromptType.CHAT, "System body", "Description");
        when(systemPromptRepository.findByName("test-prompt")).thenReturn(Optional.of(prompt));

        // Populate cache via untrimmed call
        systemPromptService.getPrompt("  test-prompt  ");
        verify(systemPromptRepository, times(1)).findByName("test-prompt");

        // Delete with trimmed name
        systemPromptService.deletePrompt("test-prompt");
        verify(systemPromptRepository, times(1)).delete("test-prompt");

        // Subsequent getPrompt with untrimmed name must MISS cache
        when(systemPromptRepository.findByName("test-prompt")).thenReturn(Optional.empty());
        Optional<SystemPrompt> afterDelete = systemPromptService.getPrompt("  test-prompt  ");
        assertTrue(afterDelete.isEmpty());
        verify(systemPromptRepository, times(2)).findByName("test-prompt");
    }

    @Test
    void savePromptWithUntrimmedNameEvictsTrimmedCacheEntry() {
        SystemPrompt oldPrompt =
            new SystemPrompt("test-prompt", SystemPromptType.CHAT, "Body V1", "Desc");
        SystemPrompt newPrompt =
            new SystemPrompt("test-prompt", SystemPromptType.CHAT, "Body V2", "Desc");
        when(systemPromptRepository.findByName("test-prompt")).thenReturn(Optional.of(oldPrompt));

        // Populate cache
        systemPromptService.getPrompt("test-prompt");
        verify(systemPromptRepository, times(1)).findByName("test-prompt");

        // Save with untrimmed name
        systemPromptService.savePrompt("  test-prompt  ", SystemPromptType.CHAT, "Body V2", "Desc",
            AREA);

        // Subsequent getPrompt must MISS cache
        when(systemPromptRepository.findByName("test-prompt")).thenReturn(Optional.of(newPrompt));
        Optional<SystemPrompt> afterSave = systemPromptService.getPrompt("test-prompt");
        assertTrue(afterSave.isPresent());
        assertEquals("Body V2", afterSave.get().systemPrompt());
        verify(systemPromptRepository, times(2)).findByName("test-prompt");
    }

    @Test
    void nullAndBlankNamesSafelyBypassCacheWithoutSpelExceptions() {
        assertDoesNotThrow(() -> assertTrue(systemPromptService.getPrompt(null).isEmpty()));
        assertDoesNotThrow(() -> assertTrue(systemPromptService.getPrompt("").isEmpty()));
        assertDoesNotThrow(() -> assertTrue(systemPromptService.getPrompt("   ").isEmpty()));
        assertDoesNotThrow(() -> assertTrue(systemPromptService.getPrompt("\t\n\r").isEmpty()));

        assertThrows(IllegalArgumentException.class,
            () -> systemPromptService.savePrompt(null, SystemPromptType.CHAT, "B", "D", AREA));
        assertThrows(IllegalArgumentException.class,
            () -> systemPromptService.savePrompt("", SystemPromptType.CHAT, "B", "D", AREA));
        assertThrows(IllegalArgumentException.class,
            () -> systemPromptService.savePrompt("   ", SystemPromptType.CHAT, "B", "D", AREA));
        assertThrows(IllegalArgumentException.class,
            () -> systemPromptService.savePrompt("\t\n", SystemPromptType.CHAT, "B", "D", AREA));

        assertThrows(IllegalArgumentException.class, () -> systemPromptService.deletePrompt(null));
        assertThrows(IllegalArgumentException.class, () -> systemPromptService.deletePrompt(""));
        assertThrows(IllegalArgumentException.class, () -> systemPromptService.deletePrompt("   "));
        assertThrows(IllegalArgumentException.class,
            () -> systemPromptService.deletePrompt("\t\n"));

        verifyNoInteractions(systemPromptRepository);
    }

    @Test
    void getPromptWithTabAndNewlineNormalizesAndHitsCache() {
        SystemPrompt prompt =
            new SystemPrompt("special-prompt", SystemPromptType.CHAT, "Body", "Desc");
        when(systemPromptRepository.findByName("special-prompt")).thenReturn(Optional.of(prompt));

        Optional<SystemPrompt> first = systemPromptService.getPrompt("\t\nspecial-prompt\r\n");
        assertTrue(first.isPresent());
        verify(systemPromptRepository, times(1)).findByName("special-prompt");

        Optional<SystemPrompt> second = systemPromptService.getPrompt("special-prompt");
        assertTrue(second.isPresent());
        verify(systemPromptRepository, times(1)).findByName("special-prompt");
    }

    @Test
    void getPromptWithInternalSpacesPreservesInternalWhitespace() {
        SystemPrompt prompt =
            new SystemPrompt("prompt with spaces", SystemPromptType.CHAT, "Body", "Desc");
        when(systemPromptRepository.findByName("prompt with spaces"))
            .thenReturn(Optional.of(prompt));

        Optional<SystemPrompt> first = systemPromptService.getPrompt("   prompt with spaces   ");
        assertTrue(first.isPresent());
        verify(systemPromptRepository, times(1)).findByName("prompt with spaces");

        Optional<SystemPrompt> second = systemPromptService.getPrompt("prompt with spaces");
        assertTrue(second.isPresent());
        verify(systemPromptRepository, times(1)).findByName("prompt with spaces");
    }

    @Test
    void savePromptWithTabAndNewlineEvictsNormalizedKey() {
        SystemPrompt prompt = new SystemPrompt("prompt-evict", SystemPromptType.CHAT, "B1", "D1");
        when(systemPromptRepository.findByName("prompt-evict")).thenReturn(Optional.of(prompt));

        systemPromptService.getPrompt("prompt-evict");
        verify(systemPromptRepository, times(1)).findByName("prompt-evict");

        systemPromptService.savePrompt("\t prompt-evict \n", SystemPromptType.CHAT, "B2", "D2",
            AREA);

        when(systemPromptRepository.findByName("prompt-evict")).thenReturn(
            Optional.of(new SystemPrompt("prompt-evict", SystemPromptType.CHAT, "B2", "D2")));
        systemPromptService.getPrompt("prompt-evict");
        verify(systemPromptRepository, times(2)).findByName("prompt-evict");
    }

    @Test
    void deletePromptWithTabAndNewlineEvictsNormalizedKey() {
        SystemPrompt prompt = new SystemPrompt("prompt-del", SystemPromptType.CHAT, "B1", "D1");
        when(systemPromptRepository.findByName("prompt-del")).thenReturn(Optional.of(prompt));

        systemPromptService.getPrompt("prompt-del");
        verify(systemPromptRepository, times(1)).findByName("prompt-del");

        systemPromptService.deletePrompt("  \n\tprompt-del  ");
        verify(systemPromptRepository, times(1)).delete("prompt-del");

        when(systemPromptRepository.findByName("prompt-del")).thenReturn(Optional.empty());
        systemPromptService.getPrompt("prompt-del");
        verify(systemPromptRepository, times(2)).findByName("prompt-del");
    }

    @Test
    void concurrentSystemPromptGetAndSaveStressTest() throws Exception {
        final String promptName = "stress-prompt";
        final SystemPrompt prompt =
            new SystemPrompt(promptName, SystemPromptType.CHAT, "Body", "Desc");
        when(systemPromptRepository.findByName(promptName)).thenReturn(Optional.of(prompt));

        int threadCount = 20;
        int iterationsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger getSuccesses = new AtomicInteger(0);
        AtomicInteger saveSuccesses = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < iterationsPerThread; j++) {
                        if (threadId % 3 == 0) {
                            systemPromptService.savePrompt(promptName, SystemPromptType.CHAT,
                                "Body " + j, "Desc " + j, AREA);
                            saveSuccesses.incrementAndGet();
                        } else {
                            Optional<SystemPrompt> res = systemPromptService.getPrompt(promptName);
                            if (res.isPresent()) {
                                getSuccesses.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertTrue(saveSuccesses.get() > 0);
        assertTrue(getSuccesses.get() > 0);
    }

    @Test
    void findChatPromptHitsCacheAndDoesNotInvokeRepositoryTwice() {
        SystemPrompt prompt =
            new SystemPrompt("cached-chat", SystemPromptType.CHAT, "Body", "Desc");
        when(systemPromptRepository.findByName("cached-chat")).thenReturn(Optional.of(prompt));

        Optional<SystemPrompt> first = systemPromptService.findChatPrompt("cached-chat");
        assertTrue(first.isPresent());
        assertEquals("cached-chat", first.get().name());
        verify(systemPromptRepository, times(1)).findByName("cached-chat");

        Optional<SystemPrompt> second = systemPromptService.findChatPrompt("cached-chat");
        assertTrue(second.isPresent());
        assertEquals("cached-chat", second.get().name());
        verify(systemPromptRepository, times(1)).findByName("cached-chat");
    }

    @Test
    void setDefaultChatPromptNameImmediatelyUpdatesFindChatPromptDefaultChat() {
        SystemPrompt promptA =
            new SystemPrompt("prompt-a", SystemPromptType.CHAT, "Body A", "Desc A");
        SystemPrompt promptB =
            new SystemPrompt("prompt-b", SystemPromptType.CHAT, "Body B", "Desc B");
        when(systemPromptRepository.findByName("prompt-a")).thenReturn(Optional.of(promptA));
        when(systemPromptRepository.findByName("prompt-b")).thenReturn(Optional.of(promptB));

        systemPromptService.setDefaultChatPromptName("prompt-a");
        Optional<SystemPrompt> first = systemPromptService.findChatPrompt("default-chat");
        assertTrue(first.isPresent());
        assertEquals("Body A", first.get().systemPrompt());

        systemPromptService.setDefaultChatPromptName("prompt-b");
        Optional<SystemPrompt> second = systemPromptService.findChatPrompt("default-chat");
        assertTrue(second.isPresent());
        assertEquals("Body B", second.get().systemPrompt());
    }

    @Test
    void importPromptFromMarkdownEvictsCacheForSubsequentFindChatPrompt() {
        SystemPrompt promptV1 =
            new SystemPrompt("import-chat", SystemPromptType.CHAT, "V1 Body", "Desc");
        when(systemPromptRepository.findByName("import-chat")).thenReturn(Optional.of(promptV1));

        Optional<SystemPrompt> before = systemPromptService.findChatPrompt("import-chat");
        assertTrue(before.isPresent());
        assertEquals("V1 Body", before.get().systemPrompt());

        SystemPrompt promptV2 =
            new SystemPrompt("import-chat", SystemPromptType.CHAT, "V2 Body", "Desc");
        when(systemPromptRepository.findByName("import-chat")).thenReturn(Optional.of(promptV2));

        String md = "---\nname: import-chat\ntype: CHAT\narea: OIM\n---\n\nV2 Body";
        systemPromptService.importPromptFromMarkdown(md, "import-chat", AREA);

        Optional<SystemPrompt> after = systemPromptService.findChatPrompt("import-chat");
        assertTrue(after.isPresent());
        assertEquals("V2 Body", after.get().systemPrompt());
    }

    @Test
    void findChatPromptAndGetPromptWithColonPrefixDoNotCollide() {
        SystemPrompt promptColon =
            new SystemPrompt("chat:alpha", SystemPromptType.CHAT, "Colon Body", "Desc");
        SystemPrompt promptAlpha =
            new SystemPrompt("alpha", SystemPromptType.CHAT, "Alpha Body", "Desc");
        when(systemPromptRepository.findByName("chat:alpha")).thenReturn(Optional.of(promptColon));
        when(systemPromptRepository.findByName("alpha")).thenReturn(Optional.of(promptAlpha));

        Optional<SystemPrompt> getColon = systemPromptService.getPrompt("chat:alpha");
        Optional<SystemPrompt> findAlpha = systemPromptService.findChatPrompt("alpha");

        assertTrue(getColon.isPresent());
        assertEquals("Colon Body", getColon.get().systemPrompt());
        assertTrue(findAlpha.isPresent());
        assertEquals("Alpha Body", findAlpha.get().systemPrompt());
    }

    @Test
    void requirePromptBypassesCacheAndReadsFreshRepositoryState() {
        SystemPrompt cached = new SystemPrompt("prompt-1", SystemPromptType.CHAT, "Body", "Desc",
            Instant.now(), Instant.now(), false, "OldArea");
        when(systemPromptRepository.findByName("prompt-1")).thenReturn(Optional.of(cached));

        // Populate cache via getPrompt
        Optional<SystemPrompt> first = systemPromptService.getPrompt("prompt-1");
        assertTrue(first.isPresent());
        assertEquals("OldArea", first.get().area());

        // Now repository state changes (e.g. area rename via CASCADE in DB without service
        // eviction)
        SystemPrompt updated = new SystemPrompt("prompt-1", SystemPromptType.CHAT, "Body", "Desc",
            Instant.now(), Instant.now(), false, "NewArea");
        when(systemPromptRepository.findByName("prompt-1")).thenReturn(Optional.of(updated));

        // getPrompt still returns cached old area
        assertEquals("OldArea", systemPromptService.getPrompt("prompt-1").get().area());

        // requirePrompt must bypass cache and see NewArea directly
        SystemPrompt required =
            systemPromptService.requirePrompt("prompt-1", SystemPromptType.CHAT);
        assertEquals("NewArea", required.area());
    }

    @Test
    void findChatPromptForDefaultChatCachesDefaultPromptNameFromAppConfig() {
        appConfigStore.put("default-chat-prompt", "cached-default");
        when(systemPromptRepository.findByName("cached-default")).thenReturn(
            Optional.of(new SystemPrompt("cached-default", SystemPromptType.CHAT, "Body", "Desc")));

        Optional<SystemPrompt> p1 = systemPromptService.findChatPrompt(null);
        Optional<SystemPrompt> p2 = systemPromptService.findChatPrompt(null);
        Optional<SystemPrompt> p3 = systemPromptService.findChatPrompt("default-chat");

        assertTrue(p1.isPresent());
        assertEquals(p1, p2);
        assertEquals(p1, p3);

        verify(appConfigRepository, times(1)).get(eq("default-chat-prompt"), any());
        verify(systemPromptRepository, times(1)).findByName("cached-default");
    }

    @Test
    void setDefaultChatPromptNameEvictsAppConfigCache() {
        when(systemPromptRepository.findByName("first-default")).thenReturn(Optional
            .of(new SystemPrompt("first-default", SystemPromptType.CHAT, "Body 1", "Desc")));
        when(systemPromptRepository.findByName("second-default")).thenReturn(Optional
            .of(new SystemPrompt("second-default", SystemPromptType.CHAT, "Body 2", "Desc")));

        systemPromptService.setDefaultChatPromptName("first-default");
        Optional<SystemPrompt> first = systemPromptService.findChatPrompt(null);
        assertTrue(first.isPresent());
        assertEquals("first-default", first.get().name());

        // Update default prompt
        systemPromptService.setDefaultChatPromptName("second-default");

        Optional<SystemPrompt> second = systemPromptService.findChatPrompt(null);
        assertTrue(second.isPresent());
        assertEquals("second-default", second.get().name());
    }
}
