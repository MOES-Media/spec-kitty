package ai.speckitty.producer.domain.order;

import static ai.speckitty.producer.domain.order.Rows.list;
import static ai.speckitty.producer.domain.order.Rows.num;
import static ai.speckitty.producer.domain.order.Rows.obj;
import static ai.speckitty.producer.domain.order.Rows.with;
import static ai.speckitty.producer.domain.order.Rows.without;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RowKeysTest {

    private static final String ID = "01J00000000000000000000ABA";
    private static final Map<String, Object> BASE =
            obj("id", ID, "time", "2026-10-13T10:00:00Z", "source", "/spec-kitty/missions/M1");

    private static OrderErrorReason reasonOf(Object row) {
        try {
            RowKeys.of(row);
        } catch (UnorderableRow e) {
            return e.reason();
        }
        throw new AssertionError("expected the row to be unorderable: " + row);
    }

    @Test
    void aClocklessRowHasHZeroAndCZero() {
        OrderKey key = RowKeys.of(BASE);
        assertThat(key).isEqualTo(new OrderKey(0, 0, Instant.parse("2026-10-13T10:00:00Z"), ID, "/spec-kitty/missions/M1"));
    }

    @Test
    void aClockedRowHasHOneAndItsClock() {
        assertThat(RowKeys.of(with(BASE, "lamportclock", num("7")))).extracting(OrderKey::h, OrderKey::c).containsExactly(1, 7);
        assertThat(RowKeys.of(with(BASE, "lamportclock", num("0")))).extracting(OrderKey::h, OrderKey::c).containsExactly(1, 0);
        assertThat(RowKeys.of(with(BASE, "lamportclock", num("2147483647")))).extracting(OrderKey::h, OrderKey::c)
                .containsExactly(1, 2147483647);
    }

    @Test
    void theIdIsNormalised() {
        assertThat(RowKeys.of(with(BASE, "id", ID.toLowerCase())).i()).isEqualTo(ID);
        assertThat(RowKeys.of(with(BASE, "id", "0190A1B2C3D47E5F8A9B0C1D2E3F4A5B")).i()).isEqualTo("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
        assertThat(RowKeys.id(with(BASE, "id", ID.toLowerCase()))).isEqualTo(ID);
    }

    @Test
    void anAbsentNullOrEmptySourceIsTheEmptyString() {
        assertThat(RowKeys.of(without(BASE, "source")).s()).isEmpty();
        assertThat(RowKeys.of(with(BASE, "source", null)).s()).isEmpty();
        assertThat(RowKeys.of(with(BASE, "source", "")).s()).isEmpty();
        assertThat(RowKeys.source(without(BASE, "source"))).isEmpty();
    }

    @Test
    void aRowThatIsNotAnObjectIsAnErrorRow() {
        for (Object row : new Object[] {null, "text", true, num("5"), list(), list(num("1"))}) {
            assertThat(reasonOf(row)).isEqualTo(OrderErrorReason.ROW_NOT_OBJECT);
        }
    }

    @Test
    void theIdIsMissingWhenAbsentNullOrEmpty() {
        assertThat(reasonOf(without(BASE, "id"))).isEqualTo(OrderErrorReason.ID_MISSING);
        assertThat(reasonOf(with(BASE, "id", null))).isEqualTo(OrderErrorReason.ID_MISSING);
        assertThat(reasonOf(with(BASE, "id", ""))).isEqualTo(OrderErrorReason.ID_MISSING);
    }

    @Test
    void anIdThatDoesNotNormaliseIsNamedAsSuch() {
        for (Object id : new Object[] {"nope", " ", ID.substring(1), num("5"), true, list(), obj(), "0".repeat(26).replace('0', 'U')}) {
            assertThat(reasonOf(with(BASE, "id", id))).as("%s", id).isEqualTo(OrderErrorReason.ID_NOT_NORMALIZABLE);
        }
    }

    @Test
    void aPresentClockMustBeAnIntegerInRange() {
        for (Object clock : new Object[] {
            null, true, false, "5", "", list(num("5")), obj(), num("5.0"), num("5e0"), num("5E0"), num("1.5"), num("-1"), num("2147483648"),
            num("99999999999999999999")
        }) {
            assertThat(reasonOf(with(BASE, "lamportclock", clock))).as("%s", clock).isEqualTo(OrderErrorReason.CLOCK_OUT_OF_RANGE);
        }
        assertThat(RowKeys.clock(without(BASE, "lamportclock"))).isEqualTo(OptionalInt.empty());
        assertThat(RowKeys.clock(with(BASE, "lamportclock", num("3")))).isEqualTo(OptionalInt.of(3));
        assertThatThrownBy(() -> RowKeys.clock(with(BASE, "lamportclock", null))).isInstanceOf(UnorderableRow.class);
    }

    @Test
    void timeMustBeAnRfc3339String() {
        for (Object time : new Object[] {null, "", "yesterday", num("5"), true, list()}) {
            assertThat(reasonOf(with(BASE, "time", time))).as("%s", time).isEqualTo(OrderErrorReason.TIME_UNPARSEABLE);
        }
        assertThat(reasonOf(without(BASE, "time"))).isEqualTo(OrderErrorReason.TIME_UNPARSEABLE);
        assertThat(RowKeys.time(BASE)).isEqualTo(Instant.parse("2026-10-13T10:00:00Z"));
    }

    @Test
    void aSourceThatIsNotAStringIsNamedAsSuch() {
        for (Object source : new Object[] {num("5"), true, list(), obj()}) {
            assertThat(reasonOf(with(BASE, "source", source))).isEqualTo(OrderErrorReason.SOURCE_INVALID);
        }
    }

    @Test
    void aRowWithSeveralDefectsHasOneReasonInTheFixedOrderIdClockTimeSource() {
        Map<String, Object> allBad = obj("id", "nope", "lamportclock", "x", "time", "no", "source", num("5"));
        assertThat(reasonOf(allBad)).isEqualTo(OrderErrorReason.ID_NOT_NORMALIZABLE);
        assertThat(reasonOf(without(allBad, "id"))).isEqualTo(OrderErrorReason.ID_MISSING);
        assertThat(reasonOf(with(allBad, "id", ID))).isEqualTo(OrderErrorReason.CLOCK_OUT_OF_RANGE);
        assertThat(reasonOf(with(with(allBad, "id", ID), "lamportclock", num("1")))).isEqualTo(OrderErrorReason.TIME_UNPARSEABLE);
        assertThat(reasonOf(with(with(with(allBad, "id", ID), "lamportclock", num("1")), "time", "2026-10-13T10:00:00Z")))
                .isEqualTo(OrderErrorReason.SOURCE_INVALID);
    }

    @Test
    void theReasonsCarryTheContractNames() {
        List<String> wire = java.util.Arrays.stream(OrderErrorReason.values()).map(OrderErrorReason::wire).toList();
        assertThat(wire).containsExactly(
                "time_unparseable", "clock_out_of_range", "id_missing", "id_not_normalizable", "source_invalid", "row_not_object");
        assertThatThrownBy(() -> new UnorderableRow(null, "x")).isInstanceOf(NullPointerException.class);
    }
}
