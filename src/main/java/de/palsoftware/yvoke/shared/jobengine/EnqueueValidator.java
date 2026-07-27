package de.palsoftware.yvoke.shared.jobengine;

import de.palsoftware.yvoke.shared.jobengine.model.*;

/**
 * SPI for domain-specific validation/normalization applied to an {@link EnqueueRequest} before it
 * is persisted. The job engine itself is domain-agnostic; domains contribute validators as Spring
 * beans (e.g. the ingest domain validates the collection/version against its catalog).
 * Implementations run in bean-discovery order and each returns a (possibly normalized) request for
 * the next to process.
 */
public interface EnqueueValidator {

    EnqueueRequest validate(EnqueueRequest req);
}
