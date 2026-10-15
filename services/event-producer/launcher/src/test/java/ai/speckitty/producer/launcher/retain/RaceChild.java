package ai.speckitty.producer.launcher.retain;

import ai.speckitty.producer.application.produce.Produce;
import ai.speckitty.producer.domain.protocol.Protocol;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.ProduceRequest;
import ai.speckitty.producer.domain.protocol.Slot;
import ai.speckitty.producer.domain.value.JsonNumber;
import ai.speckitty.producer.launcher.OperationHandler;
import ai.speckitty.producer.launcher.Wiring;
import ai.speckitty.producer.launcher.e2e.E2eTypes;
import ai.speckitty.producer.launcher.retain.RetainHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One side of the produce-versus-retain race, run as its own JVM. Arguments: mode ({@code produce} or {@code retain}), the
 * repository, a count, and a "go" file both sides wait for so that they start together.
 */
public final class RaceChild {

    private RaceChild() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path repo = Path.of(args[1]);
        int count = Integer.parseInt(args[2]);
        Path go = Path.of(args[3]);
        while (!Files.exists(go)) {
            Thread.sleep(1);
        }
        if (mode.equals("produce")) {
            produce(repo, count);
        } else {
            retain(repo, count);
        }
        System.out.flush();
    }

    private static void produce(Path repo, int count) {
        Produce produce = Wiring.withValidator(
                document -> Optional.empty(), E2eTypes.catalog());
        for (int i = 1; i <= count; i++) {
            String id = String.format("01J00000000000000000R%05d", i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("event_id", id);
            row.put("mission_slug", "demo-01J00000");
            row.put("mission_id", Repos.MISSION);
            row.put("wp_id", "WP01");
            row.put("from_lane", "planned");
            row.put("to_lane", "claimed");
            row.put("at", Repos.OLD);
            row.put("actor", "claude");
            row.put("force", Boolean.FALSE);
            row.put("execution_mode", "worktree");
            row.put("reason", null);
            row.put("review_ref", null);
            row.put("evidence", null);
            row.put("policy_metadata", null);
            ProduceReply reply = produce.produce(new ProduceRequest(
                    Protocol.VERSION, row, Slot.fromWire("status").orElseThrow(), new LinkedHashMap<>(), repo.toString(), 20000));
            System.out.println(i + " " + reply.outcome().wire());
        }
    }

    private static void retain(Path repo, int count) throws Exception {
        OperationHandler handler = new RetainHandler();
        for (int i = 0; i < count; i++) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("v", new JsonNumber("1"));
            request.put("op", "retain");
            request.put("deadline_ms", new JsonNumber("20000"));
            request.put("start_path", repo.toString());
            request.put("dry_run", Boolean.FALSE);
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("keep_runs", new JsonNumber("1"));
            policy.put("max_age_days", new JsonNumber("1"));
            request.put("policy", policy);
            Map<String, Object> reply = handler.handle(request);
            System.out.println(i + " " + ai.speckitty.producer.adapter.json.Json.render(reply.get("deleted")));
        }
    }
}
