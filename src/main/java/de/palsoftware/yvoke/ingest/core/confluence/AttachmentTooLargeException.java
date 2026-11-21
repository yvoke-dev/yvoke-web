package de.palsoftware.yvoke.ingest.core.confluence;

/** Thrown when a Confluence attachment download exceeds the configured streaming byte ceiling. */
public class AttachmentTooLargeException extends RuntimeException {

    public AttachmentTooLargeException(String message) {
        super(message);
    }
}
