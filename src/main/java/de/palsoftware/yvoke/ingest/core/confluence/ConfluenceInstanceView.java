package de.palsoftware.yvoke.ingest.core.confluence;

import jakarta.annotation.Nullable;
import java.util.UUID;

/**
 * What the connectors admin page is allowed to see about one Confluence instance.
 *
 * <p>
 * {@link ConfluenceInstance} carries {@code apiTokenEnc} and {@code tokenKeyId}. Handing that
 * record to a template puts a credential one {@code ${instance.apiTokenEnc}} away from being
 * rendered into HTML, and {@code ${instance.tokenKeyId}} would publish the key fingerprint on a
 * page. Neither component exists here, so the mistake cannot be made — the readability of the
 * stored token travels as the derived {@link TokenHealth} instead, which is exactly what an
 * operator needs to see (a key rotation becomes visible BEFORE a sync is attempted) and reveals
 * nothing.
 *
 * @param tokenHealth derived by comparing fingerprints — no decryption, so a list page cannot fail
 *        on one bad row
 */
public record ConfluenceInstanceView(UUID id,String name,String slug,String domain,String email,String space,String rootPageId,@Nullable String includeLabels,@Nullable String excludeLabels,String targetCollection,@Nullable String targetTag,boolean processAttachments,boolean buildSectionSummaries,@Nullable String summarizePrompt,boolean enabled,TokenHealth tokenHealth){

/**
 * Pre-{@code summarizePrompt} arity, so existing construction sites keep compiling.
 */
public ConfluenceInstanceView(UUID id,String name,String slug,String domain,String email,String space,String rootPageId,@Nullable String includeLabels,@Nullable String excludeLabels,String targetCollection,@Nullable String targetTag,boolean processAttachments,boolean enabled,TokenHealth tokenHealth){this(id,name,slug,domain,email,space,rootPageId,includeLabels,excludeLabels,targetCollection,targetTag,processAttachments,false,null,enabled,tokenHealth);}

/**
 * @param currentKeyId {@code SecretCipher.keyId()}, or null when encryption is disabled
 */
public static ConfluenceInstanceView of(ConfluenceInstance instance,@Nullable String currentKeyId){return new ConfluenceInstanceView(instance.id(),instance.name(),instance.slug(),instance.domain(),instance.email(),instance.space(),instance.rootPageId(),instance.includeLabels(),instance.excludeLabels(),instance.targetCollection(),instance.targetTag(),instance.processAttachments(),instance.buildSectionSummaries(),instance.summarizePrompt(),instance.enabled(),instance.tokenHealth(currentKeyId));}

/**
 * Whether the unlabelled-page count is meaningful for this instance: pages are selected by a
 * hand-applied label, so "how many pages carry none of them" only exists when at least one include
 * label is configured.
 */
public boolean hasIncludeLabels(){return includeLabels!=null&&!includeLabels.isBlank();}}
