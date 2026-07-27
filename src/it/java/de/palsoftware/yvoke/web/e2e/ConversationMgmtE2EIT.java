package de.palsoftware.yvoke.web.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import de.palsoftware.yvoke.chat.core.repository.ConversationRepository;
import de.palsoftware.yvoke.shared.user.repository.UserRepository;
import de.palsoftware.yvoke.tag.core.repository.TagRepository;
import java.util.HashMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * J5 — conversation management: add/remove tags, delete (native {@code confirm()} dialog), and the
 * read-only banner when viewing another user's {@code public}-tagged conversation. Seeds via raw
 * repositories (no ownership checks / SecurityContext needed) and asserts by seeded id, never by
 * absolute counts (Testcontainers Postgres is shared across methods).
 */
class ConversationMgmtE2EIT extends AbstractE2E {

  @Autowired private ConversationRepository conversationRepository;
  @Autowired private TagRepository tagRepository;
  @Autowired private UserRepository userRepository;

  private UUID publicConvId;

  @AfterEach
  void unpublishSeededConversation() {
    // A public-tagged conversation would otherwise stay visible in every user's sidebar for the rest
    // of the shared-context e2e run; dropping the tag removes that cross-user visibility.
    if (publicConvId != null) {
      tagRepository.removeTagFromConversation(publicConvId, "public");
      publicConvId = null;
    }
  }

  @Test
  void userCanAddAndRemoveTagOnOwnConversation() {
    loginAs("user");
    newConversation();

    // .add-tag-form renders only for an owned (non-read-only) conversation.
    page.fill(".add-tag-form input.add-tag-input", "billing");
    page.click(".add-tag-form button.add-tag-btn"); // POST /chat/{id}/tags/add -> redirect
    assertThat(page.locator(".chat-thread-tags .tag-badge")).hasCount(1);
    assertThat(page.locator(".chat-thread-tags .tag-badge")).containsText("billing");

    page.click(
        ".tag-badge:has(.tag-text:text-is('billing')) .remove-tag-form button.remove-tag-btn");
    assertThat(page.locator(".chat-thread-tags .tag-badge")).hasCount(0);
  }

  @Test
  void userCanDeleteOwnConversation() {
    loginAs("user");
    String convId = newConversation();

    assertThat(page.locator("a.conversation-link[href$='/chat/" + convId + "']")).isVisible();

    // The sidebar delete form fires a native confirm(); accept it (default is auto-dismiss).
    page.onceDialog(dialog -> dialog.accept());
    page.click(
        ".conversation-item:has(a[href$='/chat/"
            + convId
            + "']) .delete-conv-form button.delete-conv-btn");

    page.waitForURL(Pattern.compile(".*/chat$")); // POST /chat/{id}/delete -> redirect:/chat
    assertThat(page.locator("a.conversation-link[href$='/chat/" + convId + "']")).hasCount(0);
  }

  @Test
  void otherUsersPublicConversationIsReadOnly() {
    userRepository.upsert("e2e-other-oid", "other@palsoftware.local", "Other User");
    UUID otherId = userRepository.findByEntraOid("e2e-other-oid").orElseThrow().id();
    UUID convId = UUID.randomUUID();
    conversationRepository.create(convId, otherId, "Shared Convo", new HashMap<>());
    // The literal "public" tag is the whole visibility+read-only mechanism for non-owners.
    tagRepository.addTagToConversation(convId, "public");
    publicConvId = convId; // tracked for @AfterEach cleanup

    loginAs("user");
    page.navigate(url("/chat/" + convId));
    page.waitForURL(Pattern.compile(".*/chat/" + convId + "$"));

    assertThat(page.locator(".chat-read-only-banner")).isVisible();
    assertThat(page.locator(".chat-input-container")).isHidden(); // th:style display:none
    assertThat(page.locator(".add-tag-form")).hasCount(0); // rendered only when !isReadOnly
  }
}
