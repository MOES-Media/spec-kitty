package ai.speckitty.producer.domain.envelope;

import static ai.speckitty.producer.domain.order.Rows.list;
import static ai.speckitty.producer.domain.order.Rows.num;
import static ai.speckitty.producer.domain.order.Rows.obj;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.Results;
import org.junit.jupiter.api.Test;

class AttributeValueTest {

    @Test
    void stringsIntegersAndBooleansAreAttributeValues() {
        assertThat(Results.accepted(AttributeValue.fromRowValue("x"))).isEqualTo(new AttributeValue.Text("x"));
        assertThat(Results.accepted(AttributeValue.fromRowValue(""))).isEqualTo(new AttributeValue.Text(""));
        assertThat(Results.accepted(AttributeValue.fromRowValue(num("7")))).isEqualTo(new AttributeValue.Integer32(7));
        assertThat(Results.accepted(AttributeValue.fromRowValue(true))).isEqualTo(new AttributeValue.Flag(true));
        assertThat(Results.accepted(AttributeValue.fromRowValue(false))).isEqualTo(new AttributeValue.Flag(false));
    }

    @Test
    void theIntegerRangeIsThirtyTwoBitsSignedOnTheLimitAcceptedAndOneOverRefused() {
        assertThat(Results.accepted(AttributeValue.fromRowValue(num("2147483647")))).isEqualTo(new AttributeValue.Integer32(Integer.MAX_VALUE));
        assertThat(Results.accepted(AttributeValue.fromRowValue(num("-2147483648")))).isEqualTo(new AttributeValue.Integer32(Integer.MIN_VALUE));
        assertThat(Results.refusedReason(AttributeValue.fromRowValue(num("2147483648")))).isEqualTo(FaultReason.REJECTED);
        assertThat(Results.refusedReason(AttributeValue.fromRowValue(num("-2147483649")))).isEqualTo(FaultReason.REJECTED);
    }

    @Test
    void nullIsAbsentNotAValue() {
        assertThat(Results.refusedReason(AttributeValue.fromRowValue(null))).isEqualTo(FaultReason.REJECTED);
    }

    @Test
    void structuredValuesAndFractionsNeverBecomeAttributes() {
        for (Object value : new Object[] {obj("a", "b"), list("a"), list(), obj(), num("1.5"), num("1e2"), Double.valueOf(1.5), Integer.valueOf(1)}) {
            assertThat(Results.refusedReason(AttributeValue.fromRowValue(value))).as("%s", value).isEqualTo(FaultReason.REJECTED);
        }
    }
}
