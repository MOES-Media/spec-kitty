package ai.speckitty.producer.domain.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RelativePathTest {

    @Test
    void repoRelativePathsAreValid() {
        for (String path : new String[] {
            "a", ".kittify/events/publish/missions/01J0000000000000000000MSN1.jsonl", "a/b/c.jsonl", "dir.with.dots/file", "a..b/c", ".hidden", "a b/c"
        }) {
            assertThat(RelativePath.isValid(path)).as(path).isTrue();
            assertThat(new RelativePath(path).value()).isEqualTo(path);
        }
    }

    @Test
    void absoluteAndEscapingPathsAreRefused() {
        for (String path : new String[] {
            null, "", "/", "/a", "/home/someone/x", "C:/x", "c:\\x", "C:x", "\\a", "a\\b", "../a", "a/../b", "a/..", "./a", "a/./b", "a//b", "a/", "a/b/",
            "a\nb", "a\rb", "a" + (char) 0 + "b", "~/a", "file:///a"
        }) {
            assertThat(RelativePath.isValid(path)).as("%s", path).isFalse();
            assertThatThrownBy(() -> new RelativePath(path)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aTildeInsideASegmentIsFine() {
        assertThat(RelativePath.isValid("a~b/c")).isTrue();
    }
}
