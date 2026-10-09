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
import io.modelcontextprotocol.server.McpSyncServer;
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

    @Configuration
    @EnableCaching
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
    }

    @Autowired
    private PlaybookService playbookService;

    @Autowired
    private PlaybookRepository playbookRepository;

    @Autowired
    private CacheManager cacheManager;

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
        Playbook pb = new Playbook("test-pb", "Title", "Desc", "Template", List.of(), false,
            "specialist", false, null, null);
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
        Playbook pb = new Playbook("test-pb", "Title", "Desc", "Template", List.of(), false,
            "specialist", false, null, null);
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
        Playbook pb = new Playbook("test-pb", "Title", "Desc", "Template", List.of(), false,
            "specialist", false, null, null);
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
        Playbook oldPb = new Playbook("test-pb", "Old Title", "Desc", "Template", List.of(), false,
            "specialist", false, null, null);
        Playbook updatedPb = new Playbook("test-pb", "New Title", "Desc", "Template", List.of(),
            false, "specialist", false, null, null);
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.of(oldPb));

        // Populate cache with old value
        playbookService.getPlaybook("test-pb");
        verify(playbookRepository, times(1)).findByName("test-pb");

        // Save with untrimmed name (8-arg)
        playbookService.savePlaybook("  test-pb  ", "New Title", "Desc", "Template", List.of(),
            false, "specialist", false);

        // Subsequent getPlaybook must MISS cache and return updated entity
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.of(updatedPb));
        Optional<Playbook> afterSave = playbookService.getPlaybook("test-pb");
        assertTrue(afterSave.isPresent());
        assertEquals("New Title", afterSave.get().title());
        verify(playbookRepository, times(2)).findByName("test-pb");
    }

    @Test
    void savePlaybookSixArgOverloadEvictsCache() {
        Playbook oldPb = new Playbook("test-pb", "Old Title", "Desc", "Template", List.of(), false,
            "specialist", false, null, null);
        Playbook updatedPb = new Playbook("test-pb", "New Title", "Desc", "Template", List.of(),
            false, "specialist", false, null, null);
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.of(oldPb));

        // Populate cache
        playbookService.getPlaybook("test-pb");
        verify(playbookRepository, times(1)).findByName("test-pb");

        // Save via 6-arg overload
        playbookService.savePlaybook("test-pb", "New Title", "Desc", "Template", List.of(), false);

        // Subsequent getPlaybook must MISS cache
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.of(updatedPb));
        Optional<Playbook> afterSave = playbookService.getPlaybook("test-pb");
        assertTrue(afterSave.isPresent());
        assertEquals("New Title", afterSave.get().title());
        verify(playbookRepository, times(2)).findByName("test-pb");
    }

    @Test
    void savePlaybookSevenArgOverloadEvictsCache() {
        Playbook oldPb = new Playbook("test-pb", "Old Title", "Desc", "Template", List.of(), false,
            "specialist", false, null, null);
        Playbook updatedPb = new Playbook("test-pb", "New Title", "Desc", "Template", List.of(),
            false, "specialist", false, null, null);
        when(playbookRepository.findByName("test-pb")).thenReturn(Optional.of(oldPb));

        // Populate cache
        playbookService.getPlaybook("test-pb");
        verify(playbookRepository, times(1)).findByName("test-pb");

        // Save via 7-arg overload
        playbookService.savePlaybook("test-pb", "New Title", "Desc", "Template", List.of(), false,
            "specialist");

        // Subsequent getPlaybook must MISS cache
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

        assertThrows(IllegalArgumentException.class,
            () -> playbookService.savePlaybook(null, "T", "D", "B", List.of(), false));
        assertThrows(IllegalArgumentException.class,
            () -> playbookService.savePlaybook("", "T", "D", "B", List.of(), false));
        assertThrows(IllegalArgumentException.class,
            () -> playbookService.savePlaybook("   ", "T", "D", "B", List.of(), false));
        assertThrows(IllegalArgumentException.class,
            () -> playbookService.savePlaybook("\t\n", "T", "D", "B", List.of(), false));

        assertThrows(IllegalArgumentException.class, () -> playbookService.deletePlaybook(null));
        assertThrows(IllegalArgumentException.class, () -> playbookService.deletePlaybook(""));
        assertThrows(IllegalArgumentException.class, () -> playbookService.deletePlaybook("   "));
        assertThrows(IllegalArgumentException.class, () -> playbookService.deletePlaybook("\t\n"));

        verifyNoInteractions(playbookRepository);
    }

    @Test
    void getPlaybookWithTabAndNewlineNormalizesAndHitsCache() {
        Playbook pb = new Playbook("special-pb", "Title", "Desc", "Template", List.of(), false,
            "specialist", false, null, null);
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
        Playbook pb = new Playbook("pb with spaces", "Title", "Desc", "Template", List.of(), false,
            "specialist", false, null, null);
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
        Playbook pb = new Playbook("pb-trim", "Old Title", "Desc", "T1", List.of(), false,
            "specialist", false, null, null);
        when(playbookRepository.findByName("pb-trim")).thenReturn(Optional.of(pb));

        playbookService.getPlaybook("pb-trim");
        verify(playbookRepository, times(1)).findByName("pb-trim");

        playbookService.savePlaybook("\t pb-trim \n", "New Title", "Desc", "T2", List.of(), false);

        when(playbookRepository.findByName("pb-trim"))
            .thenReturn(Optional.of(new Playbook("pb-trim", "New Title", "Desc", "T2", List.of(),
                false, "specialist", false, null, null)));
        playbookService.getPlaybook("pb-trim");
        verify(playbookRepository, times(2)).findByName("pb-trim");
    }

    @Test
    void deletePlaybookWithTabAndNewlineEvictsNormalizedKey() {
        Playbook pb = new Playbook("pb-del", "Title", "Desc", "T1", List.of(), false, "specialist",
            false, null, null);
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
        final Playbook pb = new Playbook(pbName, "Title", "Desc", "Template", List.of(), false,
            "specialist", false, null, null);
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
                                "Template " + j, List.of(), false);
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
