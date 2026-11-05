package art.arcane.animus.core.rig;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The curve from one keyframe to the next, as Gloss's clip easings. */
public record Easing(Kind kind, double x1, double y1, double x2, double y2) {
    public enum Kind { LINEAR, HOLD, EASE_IN, EASE_OUT, EASE_IN_OUT, BACK_OUT, BEZIER }
    public static final Easing LINEAR = new Easing(Kind.LINEAR, 0, 0, 0, 0);
    private static final Pattern BEZIER = Pattern.compile("^bezier\\(\\s*(0(?:\\.\\d+)?|1(?:\\.0+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(0(?:\\.\\d+)?|1(?:\\.0+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)$");

    public static Optional<Easing> parse(String text) {
        return switch (text) {
            case "linear" -> Optional.of(LINEAR);
            case "hold" -> Optional.of(simple(Kind.HOLD));
            case "ease_in" -> Optional.of(simple(Kind.EASE_IN));
            case "ease_out" -> Optional.of(simple(Kind.EASE_OUT));
            case "ease_in_out" -> Optional.of(simple(Kind.EASE_IN_OUT));
            case "back_out" -> Optional.of(simple(Kind.BACK_OUT));
            default -> {
                Matcher matcher = BEZIER.matcher(text);
                if (!matcher.matches()) yield Optional.empty();
                double y1 = Double.parseDouble(matcher.group(2)), y2 = Double.parseDouble(matcher.group(4));
                if (!Double.isFinite(y1) || !Double.isFinite(y2) || Math.abs(y1) > 16 || Math.abs(y2) > 16) yield Optional.empty();
                yield Optional.of(new Easing(Kind.BEZIER, Double.parseDouble(matcher.group(1)), y1, Double.parseDouble(matcher.group(3)), y2));
            }
        };
    }
    private static Easing simple(Kind kind) { return new Easing(kind, 0, 0, 0, 0); }

    /** Progress for {@code u}, which is clamped to 0..1 first. */
    public double apply(double u) {
        double t = Math.max(0, Math.min(1, u));
        return switch (kind) {
            case LINEAR -> t;
            case HOLD -> 0;
            case EASE_IN -> t * t * t;
            case EASE_OUT -> 1 - Math.pow(1 - t, 3);
            case EASE_IN_OUT -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
            case BACK_OUT -> {
                double s = t - 1;
                yield 1 + 2.70158 * s * s * s + 1.70158 * s * s;
            }
            case BEZIER -> bezier(t);
        };
    }

    /** The CSS cubic Bezier from (0,0) to (1,1): solve x(s) = u for s, then return y(s). */
    private double bezier(double u) {
        if (u <= 0) return 0;
        if (u >= 1) return 1;
        double s = u;
        for (int i = 0; i < 8; i++) {
            double error = curve(s, x1, x2) - u, slope = slope(s, x1, x2);
            if (Math.abs(error) < 1e-12) return curve(s, y1, y2);
            if (Math.abs(slope) < 1e-9) break;
            s -= error / slope;
            if (s < 0 || s > 1) break;
        }
        double low = 0, high = 1;
        s = u;
        for (int i = 0; i < 64; i++) {
            double x = curve(s, x1, x2);
            if (Math.abs(x - u) < 1e-12) break;
            if (x < u) low = s; else high = s;
            s = (low + high) / 2;
        }
        return curve(s, y1, y2);
    }
    private static double curve(double s, double p1, double p2) {
        double inverse = 1 - s;
        return 3 * inverse * inverse * s * p1 + 3 * inverse * s * s * p2 + s * s * s;
    }
    private static double slope(double s, double p1, double p2) {
        double inverse = 1 - s;
        return 3 * inverse * inverse * p1 + 6 * inverse * s * (p2 - p1) + 3 * s * s * (1 - p2);
    }
}
