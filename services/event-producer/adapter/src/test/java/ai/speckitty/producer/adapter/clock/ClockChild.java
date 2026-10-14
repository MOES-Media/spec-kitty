package ai.speckitty.producer.adapter.clock;

import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.NODE;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.SOURCE;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.mission;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.adapter.publishlog.FilePublishLog;
import ai.speckitty.producer.adapter.repo.GitRepoLocator;
import ai.speckitty.producer.application.port.ClockStore;
import ai.speckitty.producer.application.port.FaultInjector;
import ai.speckitty.producer.application.port.FaultInjector.KillPoint;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Child process of the clock tests. {@code stamp <start> <count> <tag>} stamps {@code count} rows. {@code kill <start> <point>
 * <before> <tag>} stamps {@code before} rows and then dies, with {@code Runtime.halt}, at {@code point} of the next one.
 * Exit codes: 0 done, 3 a stamp was refused, 137 killed at the point.
 */
public final class ClockChild {

    private static final int WAIT_MILLIS = 60_000;
    private static final int KILLED = 137;

    private ClockChild() {}

    /** The row a child writes for its {@code n}-th stamp: unique per tag and number. */
    static String id(String tag, int n) {
        return String.format("%s-%04d", tag, n);
    }

    static String line(String id, int clock) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("specversion", "1.0");
        row.put("id", id);
        row.put("source", SOURCE);
        row.put("type", "ai.spec-kitty.lane.transitioned.v1");
        row.put("lamportclock", new JsonNumber(Integer.toString(clock)));
        row.put("nodeid", NODE);
        return Json.render(row);
    }

    public static void main(String[] args) {
        RepoLayout layout = ((ParseResult.Accepted<RepoLayout>) new GitRepoLocator().locate(Path.of(args[1]))).value();
        switch (args[0]) {
            case "stamp" -> {
                FileClockStore store = new FileClockStore(new FilePublishLog(), FaultInjector.NONE);
                int count = Integer.parseInt(args[2]);
                for (int i = 1; i <= count; i++) {
                    stampOne(store, layout, id(args[3], i));
                }
            }
            case "kill" -> {
                KillPoint point = KillPoint.valueOf(args[2]);
                int before = Integer.parseInt(args[3]);
                FileClockStore healthy = new FileClockStore(new FilePublishLog(), FaultInjector.NONE);
                for (int i = 1; i <= before; i++) {
                    stampOne(healthy, layout, id(args[4], i));
                }
                FileClockStore doomed = new FileClockStore(new FilePublishLog(), at -> {
                    if (at == point) {
                        Runtime.getRuntime().halt(KILLED);
                    }
                });
                stampOne(doomed, layout, id(args[4], before + 1));
            }
            default -> System.exit(2);
        }
    }

    private static void stampOne(FileClockStore store, RepoLayout layout, String id) {
        ClockStore.Request request = new ClockStore.Request(layout, NODE, mission(), SOURCE, id, WAIT_MILLIS, clock -> line(id, clock.value()));
        if (!(store.stampAndAppend(request) instanceof ParseResult.Accepted<ClockStore.Stamp>)) {
            System.exit(3);
        }
    }
}
