package de.palsoftware.yvoke.ingest.core.confluence;

import java.util.UUID;

/**
 * Announces that one instance's credential is gone or has changed — the row was deleted, or its
 * token was cleared — so anything holding derived state for it can drop it.
 *
 * <p>
 * The only listener today is {@link ConfluenceClientService}, whose cached {@code RestClient}
 * carries {@code Basic base64(email:plaintextToken)} as a default header. Announcing the change
 * instead of calling the cache directly keeps the layering intact: the repository does not depend
 * on a service.
 *
 * @param instanceId the affected {@code confluence_instances.id}
 */
public record ConfluenceInstanceCredentialsChangedEvent(UUID instanceId){}
