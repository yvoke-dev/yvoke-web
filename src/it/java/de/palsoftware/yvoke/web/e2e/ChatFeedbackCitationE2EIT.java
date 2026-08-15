package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import de.palsoftware.yvoke.document.core.model.ChunkInsert;
import de.palsoftware.yvoke.document.core.repository.DocumentRepository;
import de.palsoftware.yvoke.rag.prompt.PlaybookService;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * J4 — feedback (thumbs → htmx swap → comment persist) and citations (answer marker → citation link
 * → dialog fetched from {@code /document/citation}). Reuses the base {@link AbstractE2E} context;
 * only seeds via autowired beans.
 */
class ChatFeedbackCitationE2EIT extends AbstractE2E {

  @Autowired private PlaybookService playbookService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private void roundTrip(String playbook, String question) {
    loginAs("user");
    newConversation();
    selectPlaybookChip(playbook);
    page.fill("#chat-input", question);
    page.click("#send-stop-button");
  }

  @Test
  void thumbsDownSwapsInCommentFormAndPersistsComment() {
    playbookService.savePlaybook("e2e-fb", "E2E FB", "d", "Answer.", List.of(), false);
    stubAssistantReply("A plain assistant answer.");

    roundTrip("e2e-fb", "hi");
    assertThat(page.locator("#chat-messages")).containsText("A plain assistant answer.");

    // Vote down -> htmx swaps the JS buttons for the server fragment (which adds the comment box).
    page.click(".feedback-btn.feedback-thumbs-down");
    assertThat(page.locator(".feedback-thumbs-down.active")).isVisible();
    assertThat(page.locator(".feedback-comment-form textarea[name='comment']")).isVisible();

    // A rating alone is not persisted; only submitting a (required) comment writes it.
    page.fill(".feedback-comment-form textarea[name='comment']", "Answer was wrong.");
    page.click(".submit-comment-btn");
    assertThat(page.locator(".feedback-saved-indicator")).isVisible();

    // The indicator alone proves nothing about persistence — it renders off whatever the controller
    // put in the model, which for a long time was rebuilt from the request. Read the row back.
    Assertions
        .assertThat(jdbcTemplate.queryForList(
            "SELECT f.comment FROM message_feedback f JOIN messages m ON m.id = f.message_id "
                + "WHERE m.content = ?",
            String.class, "A plain assistant answer."))
        .containsExactly("Answer was wrong.");
  }

  @Test
  void citationMarkerRendersLinkAndOpensResolvableDialog() {
    // Seed a collection + document + one chunk so GET /document/citation?documentId=... resolves.
    String collection = "E2E-CITE-" + UUID.randomUUID();
    String version = "9.3";
    String source = "e2e_cite_manual.md";
    jdbcTemplate.update(
        "INSERT INTO collections (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
        UUID.randomUUID(), collection);
    UUID docId =
        documentRepository.upsertManualDocument(
            collection, version, source, "manual", "OIM Admin Guide");
    documentRepository.deleteContentForDocument(docId);
    documentRepository.insertChunks(
        docId,
        collection,
        version,
        source,
        "manual",
        List.of(
            new ChunkInsert(
                "OIM stands for One Identity Manager.",
                new float[1024],
                List.of("Root"),
                "Root",
                1,
                0)));

    playbookService.savePlaybook("e2e-cite", "E2E Cite", "d", "Answer.", List.of(), false);
    stubAssistantReply(
        "According to the manual [document_id=" + docId + "], OIM means One Identity Manager.");

    roundTrip("e2e-cite", "What is OIM?");

    // The [document_id=...] marker becomes a clickable citation link once the bubble finalizes.
    assertThat(page.locator("a.citation-link")).isVisible();

    page.click("a.citation-link");
    assertThat(page.locator("#citation-dialog")).isVisible();
    assertThat(page.locator("#citation-dialog-content")).containsText("OIM Admin Guide");
    assertThat(page.locator("#citation-dialog-content")).containsText("One Identity Manager");

    page.click(".citation-dialog-close-btn");
    assertThat(page.locator("#citation-dialog")).not().isVisible();
  }

  @Test
  void groupedNumberedReferencesRenderOneBadgePerNumber() {
    // Models put several sources on one claim as [1, 2]. thread.js only ever matched a single
    // number, so a grouped marker survived as unstyled literal text next to properly badged
    // single ones — visibly inconsistent in the same answer.
    playbookService.savePlaybook("e2e-numref", "E2E Numref", "d", "Answer.", List.of(), false);
    stubAssistantReply(
        "Stored in DialogObject [1]. Evaluated in memory [1, 2]. Compiled by DBCompiler [1, 2, 3].");

    roundTrip("e2e-numref", "How are selection scripts evaluated?");

    // 1 + 2 + 3 numbers across the three markers = six badges, none left as literal text.
    assertThat(page.locator("sup.citation-ref")).hasCount(6);
    assertThat(page.locator("#chat-messages")).not().containsText("[1, 2]");
    assertThat(page.locator("#chat-messages")).not().containsText("[1, 2, 3]");
    assertThat(page.locator("#chat-messages")).not().containsText("[1]");
  }
}
