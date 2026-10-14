package ai.speckitty.producer.domain.arch.planted;

import java.util.List;

/** A planted control: clean code that every rule must accept, so a rule that rejects everything is caught. */
public final class PlantedControl {

    private PlantedControl() {}

    public static int total(List<Integer> values) {
        int sum = 0;
        for (int value : values) {
            sum += value;
        }
        return sum;
    }
}
