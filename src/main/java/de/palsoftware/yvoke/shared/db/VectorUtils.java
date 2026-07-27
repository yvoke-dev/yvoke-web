package de.palsoftware.yvoke.shared.db;

public final class VectorUtils {

    private VectorUtils() {
        // Utility class — no instantiation
    }

    public static String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
