package de.palsoftware.yvoke.chat.core.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * View model for the chat sidebar: the conversation list grouped into private/public folders and
 * untagged buckets (MNT-05). Built once by {@code ChatConversationService.buildSidebar()} and
 * shared by both the chat index and the thread view, replacing the grouping logic that was
 * duplicated byte-for-byte across two controller methods.
 *
 * <p>
 * Grouping quirks are load-bearing and preserved verbatim: {@code folders}/{@code publicFolders}
 * keep case-insensitive key order, a conversation is added under <b>each</b> of its (non-"public")
 * tags (so it can appear in multiple folders), and {@code publicCount} counts folder <b>entries</b>
 * (a multi-tagged public conversation is counted once per tag).
 */
public record ConversationSidebar(List<Conversation>conversations,Map<String,List<Conversation>>folders,List<Conversation>untagged,Map<String,List<Conversation>>publicFolders,List<Conversation>publicUntagged,int publicCount,List<String>allTags,UUID currentUserId){}
