package de.palsoftware.yvoke.ingest.core.confluence;

import de.palsoftware.yvoke.shared.security.SecretCipher;
import jakarta.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One connected Confluence site/space (row of {@code confluence_instances}).
 *
 * <p>
 * {@code apiTokenEnc} is the stored ciphertext, never the plaintext token: the record travels to
 * the admin UI, and a decrypted token in a view model is one careless template expression away from
 * being rendered. Decryption happens at the point of use.
 *
 * <p>
 * The compact constructor enforces the table's own invariants (NOT NULL columns, canonical domain,
 * slug format, no empty tag) so a bad value fails at the boundary with a readable message instead
 * of surfacing as an opaque {@code DataIntegrityViolationException} from the driver.
 *
 * @param id null for an instance that has not been persisted yet
 * @param apiTokenEnc {@code SecretCipher} ciphertext, or null when no token has been set
 * @param tokenKeyId fingerprint of the key that produced {@code apiTokenEnc}; null on the row
 *        backfilled from the old singleton configuration, which recorded none. It is only ever
 *        written together with {@code apiTokenEnc} — see
 *        {@link ConfluenceInstanceRepository#upsert}.
 * @param targetTag null means "no tag"; never the empty string (see the compact constructor)
 */
public record ConfluenceInstance(@Nullable UUID id,String name,String slug,String domain,String email,@Nullable String apiTokenEnc,@Nullable String tokenKeyId,String space,String rootPageId,@Nullable String includeLabels,@Nullable String excludeLabels,String targetCollection,@Nullable String targetTag,boolean processAttachments,boolean buildSectionSummaries,@Nullable String summarizePrompt,boolean enabled,@Nullable OffsetDateTime createdAt,@Nullable OffsetDateTime updatedAt){

/**
 * Mirrors {@code ck_confluence_instances_slug_format}. The slug is embedded in the job kind
 * {@code confluence-page-import:<slug>}, which JobService parses with {@code split(":")[0]}, and it
 * is also shown verbatim in job-list labels — a colon, slash or space round-trips wrong.
 */
private static final Pattern SLUG=Pattern.compile("^[a-z0-9][a-z0-9-]*$");

/**
 * Name and slug of the single instance the V2 backfill produced, and the one the current
 * single-form connector page reads and writes. Also the fallback for a job whose kind carries no
 * {@code :<slug>} suffix (every Confluence job enqueued before instances existed).
 */
public static final String DEFAULT_SLUG="default";

public ConfluenceInstance{Objects.requireNonNull(name,"name must not be null (confluence_instances.name is NOT NULL)");Objects.requireNonNull(slug,"slug must not be null (confluence_instances.slug is NOT NULL)");Objects.requireNonNull(domain,"domain must not be null (confluence_instances.domain is NOT NULL)");Objects.requireNonNull(email,"email must not be null (confluence_instances.email is NOT NULL)");Objects.requireNonNull(space,"space must not be null (confluence_instances.space is NOT NULL)");Objects.requireNonNull(rootPageId,"rootPageId must not be null (confluence_instances.root_page_id is NOT NULL)");Objects.requireNonNull(targetCollection,"targetCollection must not be null (confluence_instances.target_collection is NOT NULL)");if(!SLUG.matcher(slug).matches()){throw new IllegalArgumentException("Invalid Confluence instance slug \""+slug+"\": expected lower-case letters, digits and dashes, starting with a letter or digit");}
// A document's source_file — its identity — is built from the domain, so "https://Acme.NET" and
// "https://acme.net/" must not mint two full sets of documents for one site. Canonicalization is
// deliberately best-effort HERE and strict in ConfluenceInstanceRepository.upsert: this
// constructor also runs on every READ, and a row restored from an older data-only dump (or
// hand-edited) may not be canonical, so throwing here would 500 the admin page that exists to fix
// it.
domain=ConfluenceDomains.canonicalizeOrKeep(domain);
// '' is not "no tag": the DB CHECK rejects it, it becomes List.of("") at enqueue (which
// hard-fails once the target collection declares any tag), and it defeats the ingest
// version-skip, which tests `:tag IS NULL`. Normalize once, here, rather than at each user.
targetTag=(targetTag==null||targetTag.isBlank())?null:targetTag.trim();
// Same normalization, same reason: the DB CHECK rejects '', and a blank would otherwise read as
// "a prompt is configured" to the enqueue validator and then resolve to nothing.
summarizePrompt=(summarizePrompt==null||summarizePrompt.isBlank())?null:summarizePrompt.trim();
// Mirrors ck_confluence_instances_summaries_need_a_prompt. Enforced here too so a caller building
// the record gets the error at construction, not as a constraint violation from the repository.
if(buildSectionSummaries&&summarizePrompt==null){throw new IllegalArgumentException("Confluence instance \""+name+"\" has section summaries enabled but names no summarize prompt; summarizing without one is what produced unusable summaries.");}}

/**
 * Pre-{@code summarizePrompt} callers: an instance with none configured reads as null, which is
 * what it is. Follows the {@code IngestionJob} precedent rather than editing the 50-odd existing
 * construction sites, none of which have a prompt to supply.
 */
public ConfluenceInstance(@Nullable UUID id,String name,String slug,String domain,String email,@Nullable String apiTokenEnc,@Nullable String tokenKeyId,String space,String rootPageId,@Nullable String includeLabels,@Nullable String excludeLabels,String targetCollection,@Nullable String targetTag,boolean processAttachments,boolean enabled,@Nullable OffsetDateTime createdAt,@Nullable OffsetDateTime updatedAt){this(id,name,slug,domain,email,apiTokenEnc,tokenKeyId,space,rootPageId,includeLabels,excludeLabels,targetCollection,targetTag,processAttachments,false,null,enabled,createdAt,updatedAt);}

/**
 * Whether the stored token is usable, decided by comparing fingerprints only — no decryption, so
 * this is safe to call for every row of a list page and cannot throw.
 *
 * @param currentKeyId {@code SecretCipher.keyId()}, or null when encryption is disabled
 */
public TokenHealth tokenHealth(@Nullable String currentKeyId){if(apiTokenEnc==null||apiTokenEnc.isBlank()){return TokenHealth.MISSING;}if(tokenKeyId==null||tokenKeyId.isBlank()){
// Legacy/backfilled row: no fingerprint was recorded, so the key is unknown. Assume the
// current one — claiming UNDECRYPTABLE would flag every pre-existing row as broken. But with
// NO key configured at all (a production dump restored into a key-less box) a ciphertext is
// provably unreadable, and that is the one state this whole mechanism exists to catch.
return currentKeyId==null&&SecretCipher.isCiphertext(apiTokenEnc)?TokenHealth.UNDECRYPTABLE:TokenHealth.OK;}return tokenKeyId.equals(currentKeyId)?TokenHealth.OK:TokenHealth.UNDECRYPTABLE;}}
