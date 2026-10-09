package de.palsoftware.yvoke.rag.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import de.palsoftware.yvoke.shared.config.CacheConfig;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.MOCK,
    properties = "app.security.mock=true")
public class PromptCacheEvictionIT {

    private static final String PB_6ARG = "it-cache-pb-6arg";
    private static final String PB_7ARG = "it-cache-pb-7arg";
    private static final String PB_8ARG = "it-cache-pb-8arg";
    private static final String PB_IMPORT = "it-cache-pb-import";
    private static final String PB_DELETE = "it-cache-pb-del";
    private static final String PROMPT_IMPORT = "it-cache-prompt-import";
    private static final String PROMPT_SAVE = "it-cache-prompt-save";
    private static final String PROMPT_DELETE = "it-cache-prompt-del";

    @Autowired
    private PlaybookService playbookService;

    @Autowired
    private SystemPromptService systemPromptService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    public void cleanup() {
        jdbcTemplate.update("DELETE FROM playbooks WHERE name IN (?, ?, ?, ?, ?)",
            PB_6ARG, PB_7ARG, PB_8ARG, PB_IMPORT, PB_DELETE);
        jdbcTemplate.update("DELETE FROM system_prompts WHERE name IN (?, ?, ?)",
            PROMPT_IMPORT, PROMPT_SAVE, PROMPT_DELETE);
        Cache pbCache = cacheManager.getCache(CacheConfig.PLAYBOOKS);
        if (pbCache != null) {
            pbCache.clear();
        }
        Cache promptCache = cacheManager.getCache(CacheConfig.SYSTEM_PROMPTS);
        if (promptCache != null) {
            promptCache.clear();
        }
    }

    @Test
    void savePlaybookSixArgOverloadEvictsCache() {
        playbookService.savePlaybook(PB_6ARG, "Initial Title", "Desc", "Template V1", List.of(),
            false);
        // Populate cache
        Optional<Playbook> cached = playbookService.getPlaybook(PB_6ARG);
        assertThat(cached).isPresent();
        assertThat(cacheManager.getCache(CacheConfig.PLAYBOOKS).get(PB_6ARG)).isNotNull();

        // Mutate via 6-arg overload (bypasses proxy in unfixed code)
        playbookService.savePlaybook(PB_6ARG, "Updated Title", "Desc", "Template V2", List.of(),
            true);

        // Verification: in unfixed code, cache entry is still V1 and NOT null
        assertThat(cacheManager.getCache(CacheConfig.PLAYBOOKS).get(PB_6ARG)).isNull();
        assertThat(playbookService.getPlaybook(PB_6ARG).orElseThrow().templateText())
            .isEqualTo("Template V2");
    }

    @Test
    void savePlaybookSevenArgOverloadEvictsCache() {
        playbookService.savePlaybook(PB_7ARG, "Initial Title", "Desc", "Template V1", List.of(),
            false, "specialist");
        // Populate cache
        assertThat(playbookService.getPlaybook(PB_7ARG)).isPresent();
        assertThat(cacheManager.getCache(CacheConfig.PLAYBOOKS).get(PB_7ARG)).isNotNull();

        // Mutate via 7-arg overload (bypasses proxy in unfixed code)
        playbookService.savePlaybook(PB_7ARG, "Updated Title", "Desc", "Template V2", List.of(),
            true, "specialist");

        // Verification: in unfixed code, cache is not evicted
        assertThat(cacheManager.getCache(CacheConfig.PLAYBOOKS).get(PB_7ARG)).isNull();
        assertThat(playbookService.getPlaybook(PB_7ARG).orElseThrow().templateText())
            .isEqualTo("Template V2");
    }

    @Test
    void savePlaybookEightArgOverloadEvictsCache() {
        playbookService.savePlaybook(PB_8ARG, "Initial Title", "Desc", "Template V1", List.of(),
            false, "specialist", false);
        // Populate cache
        assertThat(playbookService.getPlaybook(PB_8ARG)).isPresent();
        assertThat(cacheManager.getCache(CacheConfig.PLAYBOOKS).get(PB_8ARG)).isNotNull();

        // Mutate via master 8-arg method
        playbookService.savePlaybook(PB_8ARG, "Updated Title", "Desc", "Template V2", List.of(),
            true, "specialist", false);

        // Verification: cache is evicted
        assertThat(cacheManager.getCache(CacheConfig.PLAYBOOKS).get(PB_8ARG)).isNull();
        assertThat(playbookService.getPlaybook(PB_8ARG).orElseThrow().templateText())
            .isEqualTo("Template V2");
    }

    @Test
    void deletePlaybookEvictsCache() {
        playbookService.savePlaybook(PB_DELETE, "Initial Title", "Desc", "Template V1", List.of(),
            false);
        // Populate cache
        assertThat(playbookService.getPlaybook(PB_DELETE)).isPresent();
        assertThat(cacheManager.getCache(CacheConfig.PLAYBOOKS).get(PB_DELETE)).isNotNull();

        // Delete
        playbookService.deletePlaybook(PB_DELETE);

        // Verification: cache is evicted
        assertThat(cacheManager.getCache(CacheConfig.PLAYBOOKS).get(PB_DELETE)).isNull();
        assertThat(playbookService.getPlaybook(PB_DELETE)).isEmpty();
    }

    @Test
    void importPlaybookFromMarkdownEvictsCache() {
        playbookService.savePlaybook(PB_IMPORT, "Initial Title", "Desc", "Template V1", List.of(),
            false);
        // Populate cache
        assertThat(playbookService.getPlaybook(PB_IMPORT)).isPresent();
        assertThat(cacheManager.getCache(CacheConfig.PLAYBOOKS).get(PB_IMPORT)).isNotNull();

        // Mutate via importPlaybookFromMarkdown (bypasses proxy in unfixed code)
        String md = """
            ---
            name: it-cache-pb-import
            title: Imported Title
            ---
            Template V2 Imported
            """;
        playbookService.importPlaybookFromMarkdown(md, PB_IMPORT);

        // Verification: in unfixed code, cache is not evicted
        assertThat(cacheManager.getCache(CacheConfig.PLAYBOOKS).get(PB_IMPORT)).isNull();
        assertThat(playbookService.getPlaybook(PB_IMPORT).orElseThrow().templateText())
            .isEqualTo("Template V2 Imported");
    }

    @Test
    void savePromptEvictsCache() {
        systemPromptService.savePrompt(PROMPT_SAVE, SystemPromptType.CHAT, "System Prompt V1",
            "Desc");
        // Populate cache
        assertThat(systemPromptService.getPrompt(PROMPT_SAVE)).isPresent();
        assertThat(cacheManager.getCache(CacheConfig.SYSTEM_PROMPTS).get(PROMPT_SAVE)).isNotNull();

        // Mutate via savePrompt
        systemPromptService.savePrompt(PROMPT_SAVE, SystemPromptType.CHAT, "System Prompt V2",
            "Desc");

        // Verification: cache is evicted
        assertThat(cacheManager.getCache(CacheConfig.SYSTEM_PROMPTS).get(PROMPT_SAVE)).isNull();
        assertThat(systemPromptService.getPrompt(PROMPT_SAVE).orElseThrow().systemPrompt())
            .isEqualTo("System Prompt V2");
    }

    @Test
    void deletePromptEvictsCache() {
        systemPromptService.savePrompt(PROMPT_DELETE, SystemPromptType.CHAT, "System Prompt V1",
            "Desc");
        // Populate cache
        assertThat(systemPromptService.getPrompt(PROMPT_DELETE)).isPresent();
        assertThat(cacheManager.getCache(CacheConfig.SYSTEM_PROMPTS).get(PROMPT_DELETE)).isNotNull();

        // Delete
        systemPromptService.deletePrompt(PROMPT_DELETE);

        // Verification: cache is evicted
        assertThat(cacheManager.getCache(CacheConfig.SYSTEM_PROMPTS).get(PROMPT_DELETE)).isNull();
        assertThat(systemPromptService.getPrompt(PROMPT_DELETE)).isEmpty();
    }

    @Test
    void importPromptFromMarkdownEvictsCache() {
        systemPromptService.savePrompt(PROMPT_IMPORT, SystemPromptType.CHAT, "System Prompt V1",
            "Desc");
        // Populate cache
        assertThat(systemPromptService.getPrompt(PROMPT_IMPORT)).isPresent();
        assertThat(cacheManager.getCache(CacheConfig.SYSTEM_PROMPTS).get(PROMPT_IMPORT)).isNotNull();

        // Mutate via importPromptFromMarkdown (bypasses proxy in unfixed code)
        String md = """
            ---
            name: it-cache-prompt-import
            type: CHAT
            description: Imported Description
            ---
            System Prompt V2 Imported
            """;
        systemPromptService.importPromptFromMarkdown(md, PROMPT_IMPORT);

        // Verification: in unfixed code, cache is not evicted
        assertThat(cacheManager.getCache(CacheConfig.SYSTEM_PROMPTS).get(PROMPT_IMPORT)).isNull();
        assertThat(systemPromptService.getPrompt(PROMPT_IMPORT).orElseThrow().systemPrompt())
            .isEqualTo("System Prompt V2 Imported");
    }
}
