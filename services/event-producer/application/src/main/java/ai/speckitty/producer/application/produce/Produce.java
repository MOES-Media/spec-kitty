package ai.speckitty.producer.application.produce;

import ai.speckitty.producer.application.port.ClockStore;
import ai.speckitty.producer.application.port.NodeIdentity;
import ai.speckitty.producer.application.port.PublishLog;
import ai.speckitty.producer.application.port.RepoLocator;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.port.RowSchemaValidator;
import ai.speckitty.producer.domain.envelope.AttributeValue;
import ai.speckitty.producer.domain.envelope.Envelope;
import ai.speckitty.producer.domain.envelope.EnvelopeMapper;
import ai.speckitty.producer.domain.envelope.EnvelopeSource;
import ai.speckitty.producer.domain.envelope.EnvelopeTime;
import ai.speckitty.producer.domain.envelope.TypeEntry;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.ids.EventId;
import ai.speckitty.producer.domain.ids.EventIdForms;
import ai.speckitty.producer.domain.protocol.Notice;
import ai.speckitty.producer.domain.protocol.Outcome;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.ProduceRequest;
import ai.speckitty.producer.domain.protocol.Protocol;
import ai.speckitty.producer.domain.protocol.RefusalReason;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@code produce} use case: one persisted row in, one CloudEvents 1.0 line appended to the local publish log, one reply out.
 *
 * <p>The row is the only input. The id is the row's own {@code event_id}, never minted; the time is the row's own timestamp, never
 * synthesised; nothing is added to {@code data} but the one stated rename of a structured actor. The seam's keyword arguments are
 * never read. Every way this can fail is a reply with a named reason, never an exception: a producer that cannot produce must say
 * so and leave the lifecycle command alone.
 *
 * <p>The registry is data (a {@link TypeCatalog}); no code here looks at a type name.
 */
public final class Produce {

    /** What a validator that cannot decide says; it is mapped to {@link RefusalReason#VALIDATOR_UNAVAILABLE}. */
    public static final String VALIDATOR_UNAVAILABLE_VIOLATION = "validator-unavailable";

    private static final String SPECVERSION = "1.0";
    private static final String DATACONTENTTYPE = "application/json";
    private static final String EVENT_ID_MEMBER = "event_id";
    private static final String ATTRIBUTE_ID = "id";
    private static final String ATTRIBUTE_TIME = "time";
    private static final String ATTRIBUTE_MISSION_ID = "missionid";
    private static final String ATTRIBUTE_MISSION_SLUG = "missionslug";
    private static final String ATTRIBUTE_SUBJECT = "subject";
    private static final String MEMBER_MISSION_ID = "mission_id";
    private static final String MEMBER_MISSION_SLUG = "mission_slug";
    private static final String MEMBER_PROJECT_UUID = "project_uuid";
    private static final String MEMBER_AGGREGATE_ID = "aggregate_id";
    private static final JsonNumber PLACEHOLDER_CLOCK = new JsonNumber("0");

    private final RepoLocator locator;
    private final NodeIdentity nodeIdentity;
    private final ClockStore clockStore;
    private final RowSchemaValidator validator;
    private final TypeCatalog catalog;
    private final StartContext context;
    private final Function<Map<String, Object>, String> lineRenderer;

    /**
     * @param lineRenderer the deterministic one-line JSON text of a document; supplied by the composition root, because the JSON
     *     library is the adapter's
     */
    public Produce(
            RepoLocator locator,
            NodeIdentity nodeIdentity,
            ClockStore clockStore,
            RowSchemaValidator validator,
            TypeCatalog catalog,
            StartContext context,
            Function<Map<String, Object>, String> lineRenderer) {
        this.locator = locator;
        this.nodeIdentity = nodeIdentity;
        this.clockStore = clockStore;
        this.validator = validator;
        this.catalog = catalog;
        this.context = context;
        this.lineRenderer = lineRenderer;
    }

    /** Produces the row of {@code request}; always a reply. */
    public ProduceReply produce(ProduceRequest request) {
        Map<String, Object> row = request.row();
        String replyId = row.get(EVENT_ID_MEMBER) instanceof String text ? EventIdForms.normalizeForOrder(text).orElse(null) : null;
        Refuser refuser = new Refuser(replyId, request);

        if (!(locator.locate(startOf(request.startPath())) instanceof ParseResult.Accepted<RepoLayout> located)) {
            return refuser.refuse(RefusalReason.ROOT_UNRESOLVABLE, "no repository above the start path");
        }
        RepoLayout layout = located.value();
        refuser.layout = layout;

        TypeCatalog.Lookup lookup = catalog.find(row);
        if (lookup instanceof TypeCatalog.Unavailable) {
            return refuser.refuse(RefusalReason.VALIDATOR_UNAVAILABLE, "no schema registry is bound");
        }
        if (lookup instanceof TypeCatalog.Unknown unknown) {
            return refuser.refuse(RefusalReason.TYPE_UNKNOWN, unknown.detail());
        }
        RegisteredType type = ((TypeCatalog.Found) lookup).type();

        Mapping mapping = map(type, row);
        if (mapping.problem != null) {
            return refuser.refuse(RefusalReason.SCHEMA_INVALID, mapping.problem);
        }
        Map<String, Object> attributes = mapping.attributes;
        Map<String, Object> data = mapping.data;

        // FR-007: the row's own ULID, never another form and never minted.
        if (!(attributes.get(ATTRIBUTE_ID) instanceof String rawId)) {
            return refuser.refuse(RefusalReason.ID_NOT_ULID, "the row has no event id");
        }
        if (!(EventId.parseUlid(rawId) instanceof ParseResult.Accepted<EventId> id)) {
            return refuser.refuse(RefusalReason.ID_NOT_ULID, ((ParseResult.Refused<EventId>) EventId.parseUlid(rawId)).detail());
        }
        attributes.put(ATTRIBUTE_ID, id.value().value());

        // FR-008, F1: the row's own time; a row with none is refused, never given one.
        if (EnvelopeTime.fromRow(attributes.get(ATTRIBUTE_TIME)) instanceof ParseResult.Refused<String> badTime) {
            return refuser.refuse(RefusalReason.TIME_UNPARSEABLE, badTime.detail());
        }

        Supplier<Optional<Map<String, Object>>> meta = memoized(() -> metaOf(request.startPath()));
        Source source = resolveSource(type, row, attributes, layout, meta);
        if (source.refusal != null) {
            return refuser.refuse(RefusalReason.PROJECT_UNRESOLVED, source.refusal);
        }
        attributes.put("source", source.resolved.source());
        attributes.remove(ATTRIBUTE_MISSION_ID);
        if (source.resolved.missionId() != null) {
            attributes.put(ATTRIBUTE_MISSION_ID, source.resolved.missionId());
        }
        completeMissionSlug(type, row, attributes, meta);
        if (type.entry().scope() == TypeEntry.Scope.MISSION && source.resolved.subjectIsAggregate() && !attributes.containsKey(ATTRIBUTE_SUBJECT)) {
            aggregateOf(row, attributes).ifPresent(aggregate -> attributes.put(ATTRIBUTE_SUBJECT, aggregate));
        }

        String nodeId;
        try {
            nodeId = nodeIdentity.nodeId();
        } catch (RuntimeException e) {
            return refuser.refuse(RefusalReason.NODEID_UNAVAILABLE, "the node id could not be derived");
        }

        attributes.put("specversion", SPECVERSION);
        attributes.put("type", type.type());
        attributes.put("datacontenttype", DATACONTENTTYPE);
        attributes.put("dataschema", type.dataschema());
        attributes.put("schemaversion", type.schemaVersion());
        attributes.put("lamportclock", PLACEHOLDER_CLOCK);
        attributes.put("nodeid", nodeId);

        if (!(Envelope.build(attributes) instanceof ParseResult.Accepted<Envelope> built)) {
            return refuser.refuse(RefusalReason.SCHEMA_INVALID, ((ParseResult.Refused<Envelope>) Envelope.build(attributes)).detail());
        }
        Map<String, Object> document = documentOf(built.value(), data);

        Optional<String> violation;
        try {
            violation = validator.violation(document);
        } catch (RuntimeException e) {
            return refuser.refuse(RefusalReason.VALIDATOR_UNAVAILABLE, "the validator could not decide");
        }
        if (violation.isPresent()) {
            if (VALIDATOR_UNAVAILABLE_VIOLATION.equals(violation.get())) {
                return refuser.refuse(RefusalReason.VALIDATOR_UNAVAILABLE, "no schema validator is bound");
            }
            return refuser.refuse(RefusalReason.SCHEMA_INVALID, violation.get());
        }

        PublishLog.Target target = source.resolved.missionId() != null
                ? new PublishLog.Target(PublishLog.Target.Kind.MISSION, source.resolved.missionId())
                : new PublishLog.Target(PublishLog.Target.Kind.PROJECT, source.resolved.source().substring(EnvelopeSource.PROJECT_PREFIX.length()));
        ClockStore.LineRenderer renderer = clock -> {
            Map<String, Object> stamped = new LinkedHashMap<>(document);
            stamped.put("lamportclock", new JsonNumber(Integer.toString(clock.value())));
            return lineRenderer.apply(stamped);
        };
        ClockStore.Request stamp = new ClockStore.Request(
                layout, nodeId, target, source.resolved.source(), id.value().value(), Math.max(1, request.deadlineMs() / 2), renderer);

        ParseResult<ClockStore.Stamp> stamped = clockStore.stampAndAppend(stamp);
        if (stamped instanceof ParseResult.Refused<ClockStore.Stamp> refusal) {
            return switch (refusal.reason()) {
                case CLOCK_OVERFLOW -> refuser.refuse(RefusalReason.CLOCK_OVERFLOW, refusal.detail());
                case BUSY -> refuser.refuse(RefusalReason.BUSY, refusal.detail());
                default -> refuser.refuse(RefusalReason.SCHEMA_INVALID, refusal.detail());
            };
        }
        ClockStore.Stamp done = ((ParseResult.Accepted<ClockStore.Stamp>) stamped).value();
        Notice notice = done.torn().map(torn -> new Notice(target.relativePath(), torn.line())).orElse(null);
        return new ProduceReply(
                Protocol.VERSION,
                done.duplicate() ? Outcome.SKIPPED_DUPLICATE : Outcome.PRODUCED,
                id.value().value(),
                type.type(),
                null,
                notice,
                null);
    }

    // ----- mapping ---------------------------------------------------------------------------------------------------------

    /** The attributes lifted from the row and the data, as the registry entry says; or a problem. */
    private static final class Mapping {
        Map<String, Object> attributes;
        Map<String, Object> data;
        String problem;
    }

    @SuppressWarnings("unchecked")
    private static Mapping map(RegisteredType type, Map<String, Object> row) {
        Mapping mapping = new Mapping();
        Map<String, Object> envelopeMembers = row;
        Object payload = null;
        if (type.shape() == Shape.PAYLOAD_ENVELOPE) {
            payload = row.get(type.payloadMember());
            if (!(payload instanceof Map<?, ?>)) {
                mapping.problem = "the " + type.payloadMember() + " member is not an object";
                return mapping;
            }
            envelopeMembers = new LinkedHashMap<>(row);
            envelopeMembers.remove(type.payloadMember());
        }
        ParseResult<EnvelopeMapper.Mapped> mapped = EnvelopeMapper.map(type.entry(), envelopeMembers);
        if (mapped instanceof ParseResult.Refused<EnvelopeMapper.Mapped> refusal) {
            mapping.problem = refusal.detail();
            return mapping;
        }
        EnvelopeMapper.Mapped parts = ((ParseResult.Accepted<EnvelopeMapper.Mapped>) mapped).value();
        mapping.attributes = new LinkedHashMap<>(parts.attributes());
        mapping.data = type.shape() == Shape.PAYLOAD_ENVELOPE ? (Map<String, Object>) payload : new LinkedHashMap<>(parts.data());

        ActorRule actorRule = type.actorRule();
        if (actorRule != null) {
            String attribute = attributeOf(type.entry(), actorRule.member());
            Object actor = row.get(actorRule.member());
            mapping.attributes.remove(attribute);
            Actors.attribute(actorRule.kind(), actor).ifPresent(value -> mapping.attributes.put(attribute, value));
            if (Actors.isBound(actor)) {
                if (mapping.data.containsKey(actorRule.bindingMember())) {
                    mapping.problem = "data member " + actorRule.bindingMember() + " would be written twice";
                    return mapping;
                }
                mapping.data = new LinkedHashMap<>(mapping.data);
                mapping.data.put(actorRule.bindingMember(), actor);
            }
        }
        for (Map.Entry<String, Map<String, String>> alias : type.dataAliases().entrySet()) {
            if (mapping.data.get(alias.getKey()) instanceof String value && alias.getValue().containsKey(value)) {
                mapping.data = new LinkedHashMap<>(mapping.data);
                mapping.data.put(alias.getKey(), alias.getValue().get(value));
            }
        }
        return mapping;
    }

    private static String attributeOf(TypeEntry entry, String member) {
        for (TypeEntry.Lift lift : entry.lifts()) {
            if (lift.rowMember().equals(member)) {
                return lift.attribute();
            }
        }
        return member;
    }

    // ----- source and Mission identity (FR-009, OD-MISSIONID (b)) ----------------------------------------------------------

    private static final class Source {
        EnvelopeSource.Resolved resolved;
        String refusal;
    }

    private Source resolveSource(
            RegisteredType type,
            Map<String, Object> row,
            Map<String, Object> attributes,
            RepoLayout layout,
            Supplier<Optional<Map<String, Object>>> meta) {
        Source source = new Source();
        Map<String, Object> payload = payloadOf(type, row);
        if (type.entry().scope() == TypeEntry.Scope.MISSION) {
            Object rowMissionId = type.shape() == Shape.FLAT ? row.get(MEMBER_MISSION_ID) : payload.get(MEMBER_MISSION_ID);
            ParseResult<EnvelopeSource.Resolved> byRow = EnvelopeSource.resolve(rowMissionId, null, null);
            if (byRow instanceof ParseResult.Accepted<EnvelopeSource.Resolved> found) {
                source.resolved = found.value();
                return source;
            }
            Object metaMissionId = meta.get().map(m -> m.get(MEMBER_MISSION_ID)).orElse(null);
            ParseResult<EnvelopeSource.Resolved> byMeta = EnvelopeSource.resolve(null, metaMissionId, null);
            if (byMeta instanceof ParseResult.Accepted<EnvelopeSource.Resolved> found) {
                source.resolved = found.value();
                return source;
            }
        }
        Optional<String> projectUuid = projectUuidOf(row, payload, layout);
        if (projectUuid.isEmpty()) {
            source.refusal = "neither a Mission id nor a project uuid was found";
            return source;
        }
        source.resolved = ((ParseResult.Accepted<EnvelopeSource.Resolved>) EnvelopeSource.resolve(null, null, projectUuid.get())).value();
        return source;
    }

    private Optional<String> projectUuidOf(Map<String, Object> row, Map<String, Object> payload, RepoLayout layout) {
        for (Object candidate : new Object[] {row.get(MEMBER_PROJECT_UUID), payload.get(MEMBER_PROJECT_UUID)}) {
            if (candidate instanceof String text && EventIdForms.isHyphenatedUuid(text)) {
                return Optional.of(text);
            }
        }
        return context.projectUuid(layout).filter(EventIdForms::isHyphenatedUuid);
    }

    /** The row's {@code missionslug}: its own, else its payload's, else {@code meta.json}'s; an empty one is no slug. */
    private static void completeMissionSlug(
            RegisteredType type, Map<String, Object> row, Map<String, Object> attributes, Supplier<Optional<Map<String, Object>>> meta) {
        if (type.entry().scope() != TypeEntry.Scope.MISSION) {
            return;
        }
        if (attributes.get(ATTRIBUTE_MISSION_SLUG) instanceof String own && !own.isEmpty()) {
            return;
        }
        attributes.remove(ATTRIBUTE_MISSION_SLUG);
        if (payloadOf(type, row).get(MEMBER_MISSION_SLUG) instanceof String fromPayload && !fromPayload.isEmpty()) {
            attributes.put(ATTRIBUTE_MISSION_SLUG, fromPayload);
        } else if (meta.get().map(m -> m.get(MEMBER_MISSION_SLUG)).orElse(null) instanceof String fromMeta && !fromMeta.isEmpty()) {
            attributes.put(ATTRIBUTE_MISSION_SLUG, fromMeta);
        }
    }

    /** The aggregate of a row under the project fallback source: its {@code aggregate_id}, else the Mission slug, else none. */
    private static Optional<String> aggregateOf(Map<String, Object> row, Map<String, Object> attributes) {
        if (row.get(MEMBER_AGGREGATE_ID) instanceof String aggregate && !aggregate.isEmpty()) {
            return Optional.of(aggregate);
        }
        if (attributes.get(ATTRIBUTE_MISSION_SLUG) instanceof String slug && !slug.isEmpty()) {
            return Optional.of(slug);
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(RegisteredType type, Map<String, Object> row) {
        if (type.shape() == Shape.PAYLOAD_ENVELOPE && row.get(type.payloadMember()) instanceof Map<?, ?> payload) {
            return (Map<String, Object>) payload;
        }
        return Map.of();
    }

    private Optional<Map<String, Object>> metaOf(String startPath) {
        Path start = startOf(startPath);
        return start == null ? Optional.empty() : context.metaJson(start);
    }

    private static Path startOf(String startPath) {
        try {
            return Path.of(startPath);
        } catch (InvalidPathException e) {
            return Path.of("/nonexistent-start-path-" + Integer.toHexString(startPath.hashCode()));
        }
    }

    private static <T> Supplier<T> memoized(Supplier<T> supplier) {
        Object[] cell = new Object[2];
        return () -> {
            if (cell[1] == null) {
                cell[0] = supplier.get();
                cell[1] = Boolean.TRUE;
            }
            @SuppressWarnings("unchecked")
            T value = (T) cell[0];
            return value;
        };
    }

    // ----- document and replies ---------------------------------------------------------------------------------------------

    private static Map<String, Object> documentOf(Envelope envelope, Map<String, Object> data) {
        Map<String, Object> document = new LinkedHashMap<>();
        envelope.attributes().forEach((name, value) -> document.put(name, switch (value) {
            case AttributeValue.Text text -> text.value();
            case AttributeValue.Integer32 number -> new JsonNumber(Integer.toString(number.value()));
            case AttributeValue.Flag flag -> flag.value();
        }));
        document.put("data", data);
        return document;
    }

    /** Builds refusals: one line, bounded, and never the root or the start path. */
    private static final class Refuser {
        private final String eventId;
        private final ProduceRequest request;
        RepoLayout layout;

        Refuser(String eventId, ProduceRequest request) {
            this.eventId = eventId;
            this.request = request;
        }

        ProduceReply refuse(RefusalReason reason, String detail) {
            return new ProduceReply(Protocol.VERSION, Outcome.REFUSED, eventId, null, reason, null, oneLine(detail));
        }

        private String oneLine(String detail) {
            String text = detail;
            if (layout != null) {
                text = text.replace(layout.gitCommonDir().toString(), "<repo>").replace(layout.canonicalRoot().toString(), "<repo>");
            }
            text = text.replace(request.startPath(), "<start>");
            text = text.replaceAll("[\\p{Cntrl}]+", " ").strip();
            if (text.isEmpty()) {
                text = reasonless();
            }
            return text.length() > ProduceReply.MAX_DETAIL_LENGTH ? text.substring(0, ProduceReply.MAX_DETAIL_LENGTH) : text;
        }

        private static String reasonless() {
            return "refused";
        }
    }
}
