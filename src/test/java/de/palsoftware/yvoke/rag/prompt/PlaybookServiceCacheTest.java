package de.palsoftware.yvoke.rag.prompt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.area.core.AreaService;
import de.palsoftware.yvoke.shared.config.CacheConfig;
import io.modelcontextprotocol.server.McpSyncServer;
import java.time.Instant;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(
    classes = {PlaybookService.class, CacheConfig.class, PlaybookServiceCacheTest.TestConfig.class})
class PlaybookServiceCacheTest {

    private static final String AREA = "OIM";

    @Configuration
    @EnableCaching(proxyTargetClass = true)
    static class TestConfig {
        @Bean
        PlaybookRepository playbookRepository() {
            return mock(PlaybookRepository.class);
        }

        @Bean
        ObjectProvider<McpSyncServer> mcpSyncServer() {
            @SuppressWarnings("unchecked")
            ObjectProvider<McpSyncServer> provider = mock(ObjectProvider.class);
            return provider;
        }

        @Bean
        AreaService areaService() {
            AreaService mock = mock(AreaService.class);
            when(mock.requireArea(any()))
                .thenAnswer(inv -> inv.getArgument(0) != null ? inv.getArgument(0) : AREA);
            return mock;
        }

        @Bean
        SystemPromptService systemPromptService() {
            return mock(SystemPromptService.class);
        }
    }

    @Autowired
    private PlaybookService playbookService;

    @Autowired
    private PlaybookRepository playbookRepository;

    @Autowired
    private CacheManager cacheManager;

    private static Playbook testPlaybook(String name, String title, String templateText) {
        return new Playbook(name, title, "Desc", templateText, List.of(), false, "specialist",
            false, Instant.now(), Instant.now(), false, AREA);
    }

    @BeforeEach
    void clearCache() {
        reset(playbookRepository);
        Cache cache = cacheManager.getCache(CacheConfig.PLAYBOOKS);
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    void getPlaybookNormalizesWhitespaceKeyAndHitsCache() {
        Playbook pb = testPlaybook("test-pb", "Title", "Template");
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.of(pb));

        // Untrimmed call: fetches from repository and caches under normalized key
        Optional<Playbook> first = playbookService.getPlaybook("  test-pb  ");
        assertTrue(first.isPresent());
        verify(playbookRepository, times(1)).findByName("test-pb");

        // Trimmed call: must HIT cache and NOT invoke repository a second time
        Optional<Playbook> second = playbookService.getPlaybook("test-pb");
        assertTrue(second.isPresent());
        verify(playbookRepository, times(1)).findByName("test-pb");
    }

    @Test
    void deletePlaybookWithUntrimmedNameEvictsTrimmedCacheEntry() {
        Playbook pb = testPlaybook("test-pb", "Title", "Template");
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.of(pb));

        // Populate cache via trimmed call
        playbookService.getPlaybook("test-pb");
        verify(playbookRepository, times(1)).findByName("test-pb");

        // Delete with untrimmed name
        playbookService.deletePlaybook("  test-pb  ");
        verify(playbookRepository, times(1)).delete("test-pb");

        // Subsequent getPlaybook with trimmed name must MISS cache and call repository again
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.empty());
        Optional<Playbook> afterDelete = playbookService.getPlaybook("test-pb");
        assertTrue(afterDelete.isEmpty());
        verify(playbookRepository, times(2)).findByName("test-pb");
    }

    @Test
    void deletePlaybookWithTrimmedNameEvictsUntrimmedCachedEntry() {
        Playbook pb = testPlaybook("test-pb", "Title", "Template");
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.of(pb));

        // Populate cache via untrimmed call
        playbookService.getPlaybook("  test-pb  ");
        verify(playbookRepository, times(1)).findByName("test-pb");

        // Delete with trimmed name
        playbookService.deletePlaybook("test-pb");
        verify(playbookRepository, times(1)).delete("test-pb");

        // Subsequent getPlaybook with untrimmed name must MISS cache
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.empty());
        Optional<Playbook> afterDelete = playbookService.getPlaybook("  test-pb  ");
        assertTrue(afterDelete.isEmpty());
        verify(playbookRepository, times(2)).findByName("test-pb");
    }

    @Test
    void savePlaybookWithUntrimmedNameEvictsTrimmedCacheEntry() {
        Playbook oldPb = testPlaybook("test-pb", "Old Title", "Template");
        Playbook updatedPb = testPlaybook("test-pb", "New Title", "Template");
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.of(oldPb));

        // Populate cache with old value
        playbookService.getPlaybook("test-pb");
        verify(playbookRepository, times(1)).findByName("test-pb");

        // Save with untrimmed name
        playbookService.savePlaybook("  test-pb  ", "New Title", "Desc", "Template", List.of(),
            false, "specialist", false, AREA);

        // Subsequent getPlaybook must MISS cache and return updated entity
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.of(updatedPb));
        Optional<Playbook> afterSave = playbookService.getPlaybook("test-pb");
        assertTrue(afterSave.isPresent());
        assertEquals("New Title", afterSave.get().title());
        verify(playbookRepository, times(2)).findByName("test-pb");
    }

    @Test
    void nullAndBlankNamesSafelyBypassCacheWithoutSpelExceptions() {
        assertDoesNotThrow(() -> assertTrue(playbookService.getPlaybook(null).isEmpty()));
        assertDoesNotThrow(() -> assertTrue(playbookService.getPlaybook("").isEmpty()));
        assertDoesNotThrow(() -> assertTrue(playbookService.getPlaybook("   ").isEmpty()));
        assertDoesNotThrow(() -> assertTrue(playbookService.getPlaybook("\t\n\r").isEmpty()));

        assertThrows(IllegalArgumentException.class, () -> playbookService.savePlaybook(null, "T",
            "D", "B", List.of(), false, "specialist", false, AREA));
        assertThrows(IllegalArgumentException.class, () -> playbookService.savePlaybook("", "T",
            "D", "B", List.of(), false, "specialist", false, AREA));
        assertThrows(IllegalArgumentException.class, () -> playbookService.savePlaybook("   ", "T",
            "D", "B", List.of(), false, "specialist", false, AREA));
        assertThrows(IllegalArgumentException.class, () -> playbookService.savePlaybook("\t\n", "T",
            "D", "B", List.of(), false, "specialist", false, AREA));

        assertThrows(IllegalArgumentException.class, () -> playbookService.deletePlaybook(null));
        assertThrows(IllegalArgumentException.class, () -> playbookService.deletePlaybook(""));
        assertThrows(IllegalArgumentException.class, () -> playbookService.deletePlaybook("   "));
        assertThrows(IllegalArgumentException.class, () -> playbookService.deletePlaybook("\t\n"));

        verifyNoInteractions(playbookRepository);
    }

    @Test
    void getPlaybookWithTabAndNewlineNormalizesAndHitsCache() {
        Playbook pb = testPlaybook("special-pb", "Title", "Template");
        when(playbookRepository.findByName("special-pb")).thenReturn(Optional.of(pb));

        Optional<Playbook> first = playbookService.getPlaybook("\t\nspecial-pb\r\n");
        assertTrue(first.isPresent());
        verify(playbookRepository, times(1)).findByName("special-pb");

        Optional<Playbook> second = playbookService.getPlaybook("special-pb");
        assertTrue(second.isPresent());
        verify(playbookRepository, times(1)).findByName("special-pb");
    }

    @Test
    void getPlaybookWithInternalSpacesPreservesInternalWhitespace() {
        Playbook pb = testPlaybook("pb with spaces", "Title", "Template");
        when(playbookRepository.findByName("pb with spaces")).thenReturn(Optional.of(pb));

        Optional<Playbook> first = playbookService.getPlaybook("   pb with spaces   ");
        assertTrue(first.isPresent());
        verify(playbookRepository, times(1)).findByName("pb with spaces");

        Optional<Playbook> second = playbookService.getPlaybook("pb with spaces");
        assertTrue(second.isPresent());
        verify(playbookRepository, times(1)).findByName("pb with spaces");
    }

    @Test
    void savePlaybookWithTabAndNewlineEvictsNormalizedKey() {
        Playbook pb = testPlaybook("pb-trim", "Old Title", "T1");
        when(playbookRepository.findByName("pb-trim")).thenReturn(Optional.of(pb));

        playbookService.getPlaybook("pb-trim");
        verify(playbookRepository, times(1)).findByName("pb-trim");

        playbookService.savePlaybook("\t pb-trim \n", "New Title", "Desc", "T2", List.of(), false,
            "specialist", false, AREA);

        when(playbookRepository.findByName("pb-trim"))
            .thenReturn(Optional.of(testPlaybook("pb-trim", "New Title", "T2")));
        playbookService.getPlaybook("pb-trim");
        verify(playbookRepository, times(2)).findByName("pb-trim");
    }

    @Test
    void deletePlaybookWithTabAndNewlineEvictsNormalizedKey() {
        Playbook pb = testPlaybook("pb-del", "Title", "T1");
        when(playbookRepository.findByName("pb-del")).thenReturn(Optional.of(pb));

        playbookService.getPlaybook("pb-del");
        verify(playbookRepository, times(1)).findByName("pb-del");

        playbookService.deletePlaybook("\r\n\t pb-del \t ");
        verify(playbookRepository, times(1)).delete("pb-del");

        when(playbookRepository.findByName("pb-del")).thenReturn(Optional.empty());
        playbookService.getPlaybook("pb-del");
        verify(playbookRepository, times(2)).findByName("pb-del");
    }

    @Test
    void concurrentPlaybookGetAndSaveStressTest() throws Exception {
        final String pbName = "stress-pb";
        final Playbook pb = testPlaybook(pbName, "Title", "Template");
        when(playbookRepository.findByName(pbName)).thenReturn(Optional.of(pb));

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
                            playbookService.savePlaybook(pbName, "Title " + j, "Desc",
                                "Template " + j, List.of(), false, "specialist", false, AREA);
                            saveSuccesses.incrementAndGet();
                        } else {
                            Optional<Playbook> res = playbookService.getPlaybook(pbName);
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
