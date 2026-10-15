package ai.speckitty.producer.adapter.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.domain.value.JsonNumber;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The validator checks a built document against the composed row schema of its own type, and says where, never what. */
class RegistryValidatorTest {

    private final RegistryValidator validator = new RegistryValidator(ContractRegistry.load(new MiniContract()));

    private static Map<String, Object> document(Object name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("type", MiniContract.CREATED);
        document.put("lamportclock", new JsonNumber("1"));
        document.put("data", data);
        return document;
    }

    @Test
    void aDocumentThatMatchesItsSchemaHasNoViolation() {
        assertThat(validator.violation(document("a"))).isEmpty();
    }

    @Test
    void anEnumViolationNamesThePointerAndTheKeywordButNotTheValue() {
        Optional<String> violation = validator.violation(document("zzz-secret-value"));

        assertThat(violation).hasValueSatisfying(v -> assertThat(v).startsWith("/data/name enum").doesNotContain("zzz-secret-value"));
    }

    @Test
    void anUndeclaredMemberAndAMissingRequiredMemberAreNamed() {
        Map<String, Object> extra = document("a");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) extra.get("data");
        data.put("bogus", new JsonNumber("1"));
        Map<String, Object> missing = document("a");
        ((Map<?, ?>) missing.get("data")).clear();

        assertThat(validator.violation(extra)).hasValueSatisfying(v -> assertThat(v).contains("additionalProperties"));
        assertThat(validator.violation(missing)).hasValueSatisfying(v -> assertThat(v).startsWith("/data required"));
    }

    @Test
    void aNumberOfTheWrongKindIsNamed() {
        Map<String, Object> text = document("a");
        text.put("lamportclock", "1");
        Map<String, Object> big = document("a");
        big.put("lamportclock", new JsonNumber("2147483648"));

        assertThat(validator.violation(text)).hasValueSatisfying(v -> assertThat(v).startsWith("/lamportclock type"));
        assertThat(validator.violation(big)).hasValueSatisfying(v -> assertThat(v).startsWith("/lamportclock maximum"));
    }

    @Test
    void aTypeTheRegistryDoesNotHoldHasNoSchemaAndIsAViolation() {
        Map<String, Object> unknown = document("a");
        unknown.put("type", "ai.spec-kitty.nope.v1");
        Map<String, Object> untyped = document("a");
        untyped.remove("type");
        Map<String, Object> numeric = document("a");
        numeric.put("type", 7);

        assertThat(validator.violation(unknown)).hasValueSatisfying(v -> assertThat(v).startsWith("/type no-schema"));
        assertThat(validator.violation(untyped)).hasValueSatisfying(v -> assertThat(v).startsWith("/type no-schema"));
        assertThat(validator.violation(numeric)).hasValueSatisfying(v -> assertThat(v).startsWith("/type no-schema"));
    }

    @Test
    void theSchemaOfOneTypeIsNotUsedForAnother() {
        Map<String, Object> asLane = document("a");
        asLane.put("type", MiniContract.LANE);

        assertThat(validator.violation(asLane)).isEmpty();
        MiniContract contract = new MiniContract();
        contract.files.put("composed/rows/lane.transitioned.v1.row.schema.json", MiniContract.rowSchema(MiniContract.LANE, "lane.transitioned.v1").replace("\"a\",\"b\"", "\"only-lane\""));
        RegistryValidator strict = new RegistryValidator(ContractRegistry.load(contract));

        assertThat(strict.violation(asLane)).isPresent();
        assertThat(strict.violation(document("a"))).isEmpty();
    }

    @Test
    void manyViolationsAreListedInOrderAndBounded() {
        Map<String, Object> bad = document("zzz");
        bad.put("lamportclock", "x");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) bad.get("data");
        for (int i = 0; i < 40; i++) {
            data.put("extra" + "x".repeat(20) + i, new JsonNumber(Integer.toString(i)));
        }

        String violation = validator.violation(bad).orElseThrow();

        assertThat(violation.lines().count()).isEqualTo(1);
        assertThat(violation.length()).isLessThanOrEqualTo(400);
        assertThat(violation).startsWith("/data additionalProperties").contains("; /data/name enum");
        assertThat(validator.violation(bad)).as("deterministic").contains(violation);
    }

    @Test
    void aValidatorOverASchemaThatCannotBeCompiledIsRefusedAtConstruction() {
        MiniContract contract = new MiniContract();
        contract.files.put("composed/rows/mission.created.v1.row.schema.json", "{\"type\":\"object\",\"properties\":{\"data\":{\"$ref\":\"#/nowhere\"}}}");

        assertThatThrownBy(() -> new RegistryValidator(ContractRegistry.load(contract))).isInstanceOf(ContractException.class).hasMessageContaining("mission.created.v1");
    }

    @Test
    void theValidatorCountsTheSchemasItHolds() {
        assertThat(validator.schemaCount()).isEqualTo(2);
    }
}
