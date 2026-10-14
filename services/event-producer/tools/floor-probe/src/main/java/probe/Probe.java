package probe;

/** Half covered on purpose: {@code twice} is exercised by the test, {@code unused} is not (line coverage 0.50 exactly). */
public final class Probe {
    private Probe() {}

    public static int twice(int value) {
        return value * 2;
    }

    public static int unused(int value) {
        return value * 3;
    }
}
