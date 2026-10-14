package ai.speckitty.producer.launcher;

import ai.speckitty.producer.domain.protocol.Notice;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.Protocol;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.util.LinkedHashMap;
import java.util.Map;

/** The reply objects of the protocol (plan section 5), as members the JSON writer renders. Absent members are omitted, never null. */
public final class Replies {

    /** The reason of a reply to an operation no handler answers. */
    public static final String UNSUPPORTED_OPERATION = "unsupported-operation";

    private Replies() {}

    private static JsonNumber version() {
        return new JsonNumber(Integer.toString(Protocol.VERSION));
    }

    /** The reply to a {@code produce}. */
    public static Map<String, Object> produce(ProduceReply reply) {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("v", version());
        members.put("op", "produce");
        members.put("outcome", reply.outcome().wire());
        if (reply.eventId() != null) {
            members.put("event_id", reply.eventId());
        }
        if (reply.type() != null) {
            members.put("type", reply.type());
        }
        if (reply.reason() != null) {
            members.put("reason_class", reply.reasonClass().wire());
            members.put("reason", reply.reason().wire());
        }
        if (reply.notice() != null) {
            members.put("notice", notice(reply.notice()));
        }
        if (reply.detail() != null) {
            members.put("detail", reply.detail());
        }
        return members;
    }

    private static Map<String, Object> notice(Notice notice) {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("kind", Notice.KIND);
        members.put("path", notice.path().value());
        members.put("line", new JsonNumber(Integer.toString(notice.line())));
        return members;
    }

    /** The reply to an operation no handler answers; it does not echo the operation name. */
    public static Map<String, Object> unsupported() {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("v", version());
        members.put("outcome", "refused");
        members.put("reason", UNSUPPORTED_OPERATION);
        return members;
    }
}
