package ai.speckitty.producer.launcher.retain;

import ai.speckitty.producer.adapter.clock.SystemClock;
import ai.speckitty.producer.adapter.ledger.FileLedgerFacts;
import ai.speckitty.producer.adapter.ledger.localtier.ClockLockGate;
import ai.speckitty.producer.adapter.ledger.localtier.FileLocalTier;
import ai.speckitty.producer.adapter.ledger.localtier.GitIndexTrackedFiles;
import ai.speckitty.producer.adapter.repo.GitRepoLocator;
import ai.speckitty.producer.application.retain.Retain;
import ai.speckitty.producer.application.retain.RetainResult;
import ai.speckitty.producer.domain.protocol.Protocol;
import ai.speckitty.producer.domain.protocol.RelativePath;
import ai.speckitty.producer.domain.protocol.RetainItem;
import ai.speckitty.producer.domain.protocol.RetainReply;
import ai.speckitty.producer.domain.protocol.RetainRequest;
import ai.speckitty.producer.domain.retention.PolicyError;
import ai.speckitty.producer.domain.value.JsonNumber;
import ai.speckitty.producer.launcher.OperationHandler;
import ai.speckitty.producer.launcher.RequestException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The {@code retain} operation of the stdin/stdout protocol: decodes the request, runs {@link Retain} over the real adapters and
 * renders the reply (plan section 5). It is registered through {@code META-INF/services}, so the launcher's dispatcher needs no
 * edit. The request carries a policy and a start path, never the open-Mission, lock or unfinished-step facts: those are derived.
 *
 * <p>The service loader calls the public constructor without arguments; it only records that the adapters are built on first use,
 * so discovering the operation costs nothing for a {@code produce}.
 */
public final class RetainHandler implements OperationHandler {

    private static final BigInteger MAX_INT = BigInteger.valueOf(Integer.MAX_VALUE);

    private final Function<RetainRequest, RetainResult> useCase;

    /** The handler over the real file-system adapters. */
    public RetainHandler() {
        this(new Function<>() {
            private Retain retain;

            @Override
            public synchronized RetainResult apply(RetainRequest request) {
                if (retain == null) {
                    SystemClock clock = new SystemClock();
                    retain = new Retain(new GitRepoLocator(), clock, new FileLedgerFacts(), new FileLocalTier(clock),
                            new GitIndexTrackedFiles(), new ClockLockGate());
                }
                return retain.retain(request);
            }
        });
    }

    /** A handler over this use case, for tests. */
    public RetainHandler(Function<RetainRequest, RetainResult> useCase) {
        this.useCase = useCase;
    }

    @Override
    public String operation() {
        return "retain";
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> request) throws RequestException {
        return render(useCase.apply(decode(request)));
    }

    private static RetainRequest decode(Map<String, Object> request) throws RequestException {
        if (!(request.get("start_path") instanceof String startPath) || startPath.isEmpty()) {
            throw new RequestException("the start path is not a non-empty string");
        }
        int deadline = deadlineMs(request.get("deadline_ms"));
        if (deadline < 1) {
            throw new RequestException("the deadline is not a positive integer");
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        if (request.containsKey("policy")) {
            if (!(request.get("policy") instanceof Map<?, ?> raw)) {
                throw new RequestException("the policy is not an object");
            }
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                policy.put((String) entry.getKey(), entry.getValue());
            }
        }
        return new RetainRequest(Protocol.VERSION, startPath, RetainRequest.dryRunFrom(request.get("dry_run")), policy, deadline);
    }

    private static int deadlineMs(Object value) {
        if (value instanceof JsonNumber number && number.isInteger()) {
            BigInteger exact = number.integerValue().orElseThrow();
            if (exact.signum() > 0 && exact.compareTo(MAX_INT) <= 0) {
                return exact.intValueExact();
            }
        }
        return -1;
    }

    private static Map<String, Object> render(RetainResult result) {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("v", new JsonNumber(Integer.toString(Protocol.VERSION)));
        members.put("op", "retain");
        if (result instanceof RetainResult.RootUnresolvable) {
            members.put("outcome", "refused");
            members.put("reason", "root-unresolvable");
            return members;
        }
        RetainReply reply = ((RetainResult.Replied) result).reply();
        members.put("dry_run", reply.dryRun());
        members.put("eligible", items(reply.eligible()));
        members.put("kept", items(reply.kept()));
        List<Object> deleted = new ArrayList<>();
        for (RelativePath path : reply.deleted()) {
            deleted.add(path.value());
        }
        members.put("deleted", deleted);
        if (reply.policyError() != null) {
            PolicyError error = reply.policyError();
            Map<String, Object> named = new LinkedHashMap<>();
            named.put("key", error.key());
            named.put("code", error.code());
            named.put("message", error.message());
            members.put("policy_error", named);
        }
        return members;
    }

    private static List<Object> items(List<RetainItem> items) {
        List<Object> rendered = new ArrayList<>();
        for (RetainItem item : items) {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("path", item.path().value());
            member.put("reason", item.reason());
            rendered.add(member);
        }
        return rendered;
    }
}
