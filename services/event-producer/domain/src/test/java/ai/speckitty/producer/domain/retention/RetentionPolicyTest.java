package ai.speckitty.producer.domain.retention;

import static ai.speckitty.producer.domain.order.Rows.list;
import static ai.speckitty.producer.domain.order.Rows.num;
import static ai.speckitty.producer.domain.order.Rows.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RetentionPolicyTest {

    private static RetentionPolicy valid(Object keepRuns, Object maxAgeDays) {
        RetentionPolicy.Parsed parsed = RetentionPolicy.parse(keepRuns, maxAgeDays);
        assertThat(parsed).as("%s / %s", keepRuns, maxAgeDays).isInstanceOf(RetentionPolicy.Parsed.Valid.class);
        return ((RetentionPolicy.Parsed.Valid) parsed).policy();
    }

    private static PolicyError invalid(Object keepRuns, Object maxAgeDays) {
        RetentionPolicy.Parsed parsed = RetentionPolicy.parse(keepRuns, maxAgeDays);
        assertThat(parsed).as("%s / %s", keepRuns, maxAgeDays).isInstanceOf(RetentionPolicy.Parsed.Invalid.class);
        return ((RetentionPolicy.Parsed.Invalid) parsed).error();
    }

    @Test
    void absentSettingsAreTheDefaultsTwentyAndThirty() {
        assertThat(RetentionPolicy.DEFAULT_KEEP_RUNS).isEqualTo(20);
        assertThat(RetentionPolicy.DEFAULT_MAX_AGE_DAYS).isEqualTo(30);
        assertThat(valid(null, null)).isEqualTo(new RetentionPolicy(20, 30));
        assertThat(valid(num("5"), null)).isEqualTo(new RetentionPolicy(5, 30));
        assertThat(valid(null, num("7"))).isEqualTo(new RetentionPolicy(20, 7));
    }

    @Test
    void positiveIntegersAreAccepted() {
        assertThat(valid(num("1"), num("1"))).isEqualTo(new RetentionPolicy(1, 1));
        assertThat(valid(num("100"), num("365"))).isEqualTo(new RetentionPolicy(100, 365));
    }

    @Test
    void aStringOfDigitsIsAccepted() {
        assertThat(valid("20", "30")).isEqualTo(new RetentionPolicy(20, 30));
        assertThat(valid("1", "9")).isEqualTo(new RetentionPolicy(1, 9));
    }

    @Test
    void theUpperLimitIsTwoToThe31MinusOneOnTheLimitAcceptedAndOneOverRefused() {
        assertThat(valid(num("2147483647"), "2147483647")).isEqualTo(new RetentionPolicy(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThat(invalid(num("2147483648"), null).key()).isEqualTo(RetentionPolicy.KEEP_RUNS_KEY);
        assertThat(invalid(null, "2147483648").key()).isEqualTo(RetentionPolicy.MAX_AGE_DAYS_KEY);
        assertThat(invalid("99999999999999999999", null).key()).isEqualTo(RetentionPolicy.KEEP_RUNS_KEY);
    }

    @Test
    void theLowerLimitIsOneZeroAndBelowAreRefused() {
        for (Object bad : new Object[] {num("0"), num("-1"), "0", "-1", "00"}) {
            assertThat(invalid(bad, null).key()).as("%s", bad).isEqualTo(RetentionPolicy.KEEP_RUNS_KEY);
            assertThat(invalid(null, bad).key()).as("%s", bad).isEqualTo(RetentionPolicy.MAX_AGE_DAYS_KEY);
        }
    }

    @Test
    void everyNonNumericOrNonIntegerValueIsRefused() {
        for (Object bad : new Object[] {
            "abc", "", " ", " 5", "5 ", "5\n", "+5", "05", "5.0", "5e0", "1_0", "five", "０５", true, false, num("5.0"), num("1.5"), num("1e1"), list(), list(num("5")),
            obj(), obj("a", "b"), Integer.valueOf(5), Double.valueOf(5)
        }) {
            PolicyError error = invalid(bad, null);
            assertThat(error.key()).as("%s", bad).isEqualTo(RetentionPolicy.KEEP_RUNS_KEY);
            assertThat(error.code()).isEqualTo(PolicyError.INVALID_VALUE);
            assertThat(invalid(null, bad).key()).isEqualTo(RetentionPolicy.MAX_AGE_DAYS_KEY);
        }
    }

    @Test
    void whenBothAreInvalidTheFirstKeyIsNamed() {
        assertThat(invalid("abc", "def").key()).isEqualTo(RetentionPolicy.KEEP_RUNS_KEY);
    }

    @Test
    void theErrorIsNamedAndNeverEchoesTheValue() {
        PolicyError error = invalid("secret-value", null);
        assertThat(PolicyError.NAME).isEqualTo("policy_error");
        assertThat(error.message()).isEqualTo("policy_error: ledger.retention.keep_runs must be a positive integer").doesNotContain("secret");
        assertThat(error.message()).doesNotContain("\n");
    }

    @Test
    void theRecordRefusesNonPositiveLimits() {
        assertThatThrownBy(() -> new RetentionPolicy(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RetentionPolicy(1, 1).keepRuns()).isEqualTo(1);
    }
}
