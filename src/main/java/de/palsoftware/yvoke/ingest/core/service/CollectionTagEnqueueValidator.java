package de.palsoftware.yvoke.ingest.core.service;

import de.palsoftware.yvoke.collection.core.model.Collection;
import de.palsoftware.yvoke.collection.core.repository.CollectionRepository;
import de.palsoftware.yvoke.shared.jobengine.model.EnqueueRequest;
import de.palsoftware.yvoke.shared.jobengine.EnqueueValidator;
import java.util.List;
import org.springframework.stereotype.Component;
import java.util.ArrayList;

/**
 * Validates and normalizes an enqueue request against the collection catalog: the collection must
 * exist, and the requested version must be one of its tags (or null when the collection is
 * untagged). This is ingest-domain policy, kept out of the generic job engine.
 */
@Component
public class CollectionTagEnqueueValidator implements EnqueueValidator {

    private final CollectionRepository collectionRepository;

    public CollectionTagEnqueueValidator(CollectionRepository collectionRepository) {
        this.collectionRepository = collectionRepository;
    }

    @Override
    public EnqueueRequest validate(EnqueueRequest req) {
        Collection col = collectionRepository.findByName(req.collection().trim())
            .orElseThrow(() -> new IllegalArgumentException(
                "Collection '" + req.collection() + "' does not exist."));

        List<String> finalTags = new ArrayList<>();
        if (!col.tags().isEmpty()) {
            List<String> reqTags = req.tags();
            if (reqTags == null || reqTags.isEmpty()) {
                throw new IllegalArgumentException(
                    "Field 'tag' (or 'tags') must not be blank/empty for collection '"
                        + req.collection() + "'");
            }
            for (String tag : reqTags) {
                if (tag == null || tag.isBlank()) {
                    throw new IllegalArgumentException(
                        "Tag in requested list must not be blank for collection '"
                            + req.collection() + "'");
                }
                String trimmedTag = tag.trim();
                if (!col.tags().contains(trimmedTag)) {
                    throw new IllegalArgumentException("Tag '" + trimmedTag
                        + "' is not valid for collection '" + req.collection() + "'");
                }
                finalTags.add(trimmedTag);
            }
        }

        return new EnqueueRequest(req.kind(), req.sourceRef(), finalTags, col.name(),
            req.settings());
    }
}
