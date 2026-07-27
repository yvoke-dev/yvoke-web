package de.palsoftware.yvoke.ingest.web.admin;

import de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstance;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The create-or-update form of the connectors page, validated at the boundary.
 *
 * <p>
 * There is deliberately NO token component. The API token is submitted as a separate request
 * parameter and encrypted in
 * {@link de.palsoftware.yvoke.ingest.core.confluence.ConfluenceInstanceService#save}, so the
 * credential can neither be bound into this DTO, echoed back into the re-rendered HTML, nor logged
 * with the rest of the form.
 *
 * <p>
 * Values are trimmed in the compact constructor — i.e. BEFORE validation runs — so a
 * whitespace-padded field is normalized rather than rejected, and an empty one becomes null (which
 * {@code @NotBlank} rejects and which the optional fields want anyway).
 *
 * <p>
 * The two checkbox components are {@link Boolean}, not {@code boolean}, and the compact constructor
 * defaults a missing value to false. An unchecked HTML checkbox submits NO parameter at all, and
 * constructor binding cannot build a primitive from a missing value: it records a
 * {@code typeMismatch} error instead, which made the very first "Save Instance" of a new instance
 * fail every time — and, because the binding result already held an error,
 * {@code ModelAttributeMethodProcessor} skipped {@code @Valid} entirely, so every constraint below
 * was dead code on that path. The template also sends Spring's hidden {@code _<field>} markers; the
 * default here is what makes the DTO correct on its own, independent of the HTML.
 *
 * @param id null when creating; the row's id when editing (the upsert arbitrates on the primary
 *        key)
 */
public record ConfluenceInstanceForm(@Nullable UUID id,

@NotBlank(message="Give the instance a name, e.g. \"iCC Wiki\".")@Size(max=200,message="The name must be at most 200 characters.")String name,

@NotBlank(message="Give the instance a slug, e.g. \"icc-wiki\".")@Size(max=100,message="The slug must be at most 100 characters.")@Pattern(regexp="^[a-z0-9][a-z0-9-]*$",message="The slug must use lower-case letters, digits and dashes only, "+"and start with a letter or digit.")String slug,

@NotBlank(message="Enter the Confluence domain URL, e.g. https://company.atlassian.net.")@Size(max=500,message="The domain URL must be at most 500 characters.")@Pattern(regexp="(?i)^https?://[^\\s/?#]+[^\\s]*$",message="The Confluence domain must be an absolute http(s) URL, "+"e.g. https://company.atlassian.net.")String domain,

@NotBlank(message="Enter the Atlassian account e-mail address.")@Size(max=320,message="The e-mail address must be at most 320 characters.")@Email(message="Enter a valid Atlassian account e-mail address.")String email,

@NotBlank(message="Enter the Confluence space key, e.g. DOCS.")@Size(max=100,message="The space key must be at most 100 characters.")String space,

@NotBlank(message="Enter the root page ID to crawl from.")@Pattern(regexp="^[0-9]+$",message="The root page ID must be numeric — it is the number in the Confluence page URL.")String rootPageId,

@Size(max=500,message="The include labels must be at most 500 characters.")@Nullable String includeLabels,

@Size(max=500,message="The exclude labels must be at most 500 characters.")@Nullable String excludeLabels,

@NotBlank(message="Choose the target collection this instance ingests into.")@Size(max=200,message="The collection name must be at most 200 characters.")String targetCollection,

@Size(max=100,message="The tag must be at most 100 characters.")@Nullable String targetTag,

@Nullable Boolean processAttachments,@Nullable Boolean enabled){

public ConfluenceInstanceForm{name=trimToNull(name);slug=trimToNull(slug);domain=trimToNull(domain);email=trimToNull(email);space=trimToNull(space);rootPageId=trimToNull(rootPageId);includeLabels=trimToNull(includeLabels);excludeLabels=trimToNull(excludeLabels);targetCollection=trimToNull(targetCollection);targetTag=trimToNull(targetTag);
// An unchecked checkbox sends nothing; "nothing" is false, not a binding failure.
processAttachments=processAttachments!=null&&processAttachments;enabled=enabled!=null&&enabled;}

private static String trimToNull(String value){if(value==null){return null;}String trimmed=value.trim();return trimmed.isEmpty()?null:trimmed;}

/**
 * The edited fields as a domain record, with BOTH credential columns null: the service attaches the
 * credential, and a null pair is what makes the repository's upsert keep the stored one.
 *
 * <p>
 * Only call this on a form that passed validation — {@link ConfluenceInstance}'s compact
 * constructor enforces the same NOT NULL and slug-format invariants and throws otherwise.
 */
public ConfluenceInstance toInstance(){return new ConfluenceInstance(id,name,slug,domain,email,null,null,space,rootPageId,includeLabels,excludeLabels,targetCollection,targetTag,processAttachments,enabled,null,null);}}
