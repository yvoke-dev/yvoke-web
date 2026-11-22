package de.palsoftware.yvoke.shared.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded stream-reading utility to prevent unbounded heap allocation from untrusted inputs. */
public final class BoundedStreams {

    private BoundedStreams() {}

    public static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalArgumentException(
                        "Content exceeds maximum allowed size of " + maxBytes + " bytes.");
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }
}
