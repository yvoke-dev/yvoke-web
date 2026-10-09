package de.palsoftware.yvoke.rag.prompt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.shared.config.CacheConfig;
import de.palsoftware.yvoke.shared.config.repository.AppConfigRepository;
import java.util.ArrayList;
import java.util.List;
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

    @Configuration
    @EnableCaching
    static class TestConfig {
        @Bean
        SystemPromptRepository systemPromptRepository() {
            return mock(SystemPromptRepository.class);
        }

        @Bean
        AppConfigRepository appConfigRepository() {
            return mock(AppConfigRepository.class);
        }
    }

    @Autowired
    private SystemPromptService systemPromptService;

    @Autowired
    private SystemPromptRepository systemPromptRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        reset(systemPromptRepository);
        Cache cache = cacheManager.getCache(CacheConfig.SYSTEM_PROMPTS);
        if (cache != null) {
            cache.clear();
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
        systemPromptService.savePrompt("  test-prompt  ", SystemPromptType.CHAT, "Body V2", "Desc");

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
            () -> systemPromptService.savePrompt(null, SystemPromptType.CHAT, "B", "D"));
        assertThrows(IllegalArgumentException.class,
            () -> systemPromptService.savePrompt("", SystemPromptType.CHAT, "B", "D"));
        assertThrows(IllegalArgumentException.class,
            () -> systemPromptService.savePrompt("   ", SystemPromptType.CHAT, "B", "D"));
        assertThrows(IllegalArgumentException.class,
            () -> systemPromptService.savePrompt("\t\n", SystemPromptType.CHAT, "B", "D"));

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

        systemPromptService.savePrompt("\t prompt-evict \n", SystemPromptType.CHAT, "B2", "D2");

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
                                "Body " + j, "Desc " + j);
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
}
