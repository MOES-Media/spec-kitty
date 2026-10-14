package ai.speckitty.producer.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodePointsTest {

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    @Test
    void equalStringsCompareEqual() {
        assertThat(CodePoints.compare("", "")).isZero();
        assertThat(CodePoints.compare("abc", "abc")).isZero();
        assertThat(CodePoints.compare(cp(0x10000), cp(0x10000))).isZero();
    }

    @Test
    void aPrefixSortsFirst() {
        assertThat(CodePoints.compare("", "a")).isNegative();
        assertThat(CodePoints.compare("ab", "abc")).isNegative();
        assertThat(CodePoints.compare("abc", "ab")).isPositive();
    }

    @Test
    void asciiOrderIsByCodePoint() {
        assertThat(CodePoints.compare("A", "a")).isNegative();
        assertThat(CodePoints.compare("/spec-kitty/m", "/spec-kitty/p")).isNegative();
    }

    @Test
    void anAstralCharacterSortsAfterEveryBasicOneAlthoughItsFirstUnitIsSmaller() {
        String astral = cp(0x10000);
        String highBmp = cp(0xFFFF);
        assertThat(astral.compareTo(highBmp)).as("UTF-16 order is the opposite").isNegative();
        assertThat(CodePoints.compare(astral, highBmp)).isPositive();
        assertThat(CodePoints.compare(highBmp, astral)).isNegative();
    }

    @Test
    void twoAstralCharactersCompareByTheirCodePoints() {
        assertThat(CodePoints.compare(cp(0x10000), cp(0x10001))).isNegative();
        assertThat(CodePoints.compare(cp(0x1F600), cp(0x10FFFF))).isNegative();
    }

    @Test
    void theDifferenceIsFoundAfterACommonPrefixOfMixedWidth() {
        assertThat(CodePoints.compare("x" + cp(0x10000) + "a", "x" + cp(0x10000) + "b")).isNegative();
        assertThat(CodePoints.compare("x" + cp(0x10000), "x" + cp(0xFFFF))).isPositive();
    }

    @Test
    void aLoneSurrogateComparesAsItsOwnCodePoint() {
        String lone = String.valueOf((char) 0xD800);
        assertThat(CodePoints.compare(lone, "a")).isPositive();
        assertThat(CodePoints.compare(lone, cp(0xE000))).isNegative();
    }
}
