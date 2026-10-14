package ai.speckitty.producer.launcher.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/** The single place the entry class is named is `producerMainClass` in launcher/build.gradle.kts; this test pins what it names. */
class EntryClassTest {

    @Test
    void theNamedEntryClassIsTheRealMainAndHasAStaticMain() throws ClassNotFoundException, NoSuchMethodException {
        String name = System.getProperty("producer.mainClass");
        assertThat(name).as("producer.mainClass system property, set from producerMainClass in launcher/build.gradle.kts").isNotBlank();
        assertThat(name).isEqualTo("ai.speckitty.producer.launcher.Main");
        Method main = Class.forName(name).getMethod("main", String[].class);
        assertThat(Modifier.isStatic(main.getModifiers())).isTrue();
        assertThat(main.getReturnType()).isEqualTo(void.class);
    }

    @Test
    void theSpikeIsGone() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Class.forName("ai.speckitty.producer.launcher.spike.SpikeMain"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
