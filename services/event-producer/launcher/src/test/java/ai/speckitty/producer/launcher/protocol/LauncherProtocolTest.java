package ai.speckitty.producer.launcher.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.launcher.Dispatcher;
import ai.speckitty.producer.launcher.Launcher;
import ai.speckitty.producer.launcher.OperationHandler;
import ai.speckitty.producer.launcher.protocol.Runs.Result;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LauncherProtocolTest {

    private static final String GOOD = "{\"v\":1,\"op\":\"produce\",\"deadline_ms\":5000,\"start_path\":\".\"}";

    private static Dispatcher produceOnly() {
        return Dispatcher.of(StubHandlers.fixed("produce"));
    }

    private static void assertUnreadable(String request) {
        Result result = Runs.run(produceOnly(), request);
        assertThat(result.exit()).as(request).isEqualTo(64);
        assertThat(result.stdout()).as("no reply is written for " + request).isEmpty();
    }

    @Test
    void aReadableRequestGetsExactlyOneJsonObjectAndALineFeedAndExitZero() {
        Result result = Runs.run(produceOnly(), GOOD);
        assertThat(result.exit()).isZero();
        assertThat(result.text()).isEqualTo("{\"op\":\"produce\",\"outcome\":\"produced\",\"v\":1}\n");
        assertThat(result.halts()).isEmpty();
    }

    @Test
    void aRequestThatIsNotOneJsonObjectIsExitSixtyFourWithNoReply() {
        assertUnreadable("");
        assertUnreadable("   ");
        assertUnreadable("not json");
        assertUnreadable("[1,2]");
        assertUnreadable("\"text\"");
        assertUnreadable("null");
        assertUnreadable(GOOD + GOOD);
        assertUnreadable(GOOD + " trailing");
        assertUnreadable(GOOD.substring(0, GOOD.length() - 1));
        assertUnreadable(String.valueOf((char) 0xFEFF) + GOOD);
        assertUnreadable("{\"v\":1,\"v\":1,\"op\":\"produce\",\"deadline_ms\":5,\"start_path\":\".\"}");
    }

    @Test
    void aRequestOfInvalidUtf8IsUnreadable() {
        byte[] bytes = {'{', '"', 'v', '"', ':', '1', ',', '"', 'x', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
        Result result = Runs.run(produceOnly(), bytes);
        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void anUnsupportedVersionIsExitSixtyFour() {
        for (String v : List.of("2", "0", "-1", "1.0", "\"1\"", "true", "null", "[1]", "1e0", "10")) {
            assertUnreadable("{\"v\":" + v + ",\"op\":\"produce\",\"deadline_ms\":5000,\"start_path\":\".\"}");
        }
        assertUnreadable("{\"op\":\"produce\",\"deadline_ms\":5000,\"start_path\":\".\"}");
    }

    @Test
    void aMissingOrNonStringOperationIsExitSixtyFour() {
        assertUnreadable("{\"v\":1,\"deadline_ms\":5000}");
        assertUnreadable("{\"v\":1,\"op\":7,\"deadline_ms\":5000}");
        assertUnreadable("{\"v\":1,\"op\":null,\"deadline_ms\":5000}");
        assertUnreadable("{\"v\":1,\"op\":[\"produce\"],\"deadline_ms\":5000}");
    }

    @Test
    void aMissingOrInvalidDeadlineIsExitSixtyFour() {
        for (String deadline : List.of("0", "-5", "1.5", "\"x\"", "null", "true", "3000000000", "1e3")) {
            assertUnreadable("{\"v\":1,\"op\":\"produce\",\"deadline_ms\":" + deadline + ",\"start_path\":\".\"}");
        }
        assertUnreadable("{\"v\":1,\"op\":\"produce\",\"start_path\":\".\"}");
    }

    @Test
    void theSmallestDeadlineAndTheLargestAreAccepted() {
        assertThat(Runs.run(produceOnly(), "{\"v\":1,\"op\":\"produce\",\"deadline_ms\":1,\"start_path\":\".\"}").exit()).isZero();
        assertThat(Runs.run(produceOnly(), "{\"v\":1,\"op\":\"produce\",\"deadline_ms\":2147483647,\"start_path\":\".\"}").exit()).isZero();
    }

    @Test
    void aHandlerThatFindsTheRequestUnreadableIsExitSixtyFour() {
        Result result = Runs.run(Dispatcher.of(StubHandlers.unreadable("produce")), GOOD);
        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void anOperationNoHandlerClaimsIsARefusedReplyNotACrash() {
        for (String op : List.of("retain", "frobnicate", "")) {
            Result result = Runs.run(produceOnly(), "{\"v\":1,\"op\":\"" + op + "\",\"deadline_ms\":5000}");
            assertThat(result.exit()).as(op).isZero();
            assertThat(result.text()).as(op).isEqualTo("{\"outcome\":\"refused\",\"reason\":\"unsupported-operation\",\"v\":1}\n");
        }
    }

    @Test
    void anUnknownOperationNeverEchoesItsName() {
        Result result = Runs.run(produceOnly(), "{\"v\":1,\"op\":\"" + String.join("/", "", "home", "someone", "secret") + "\",\"deadline_ms\":5000}");
        assertThat(result.text()).doesNotContain("secret").doesNotContain(String.join("/", "", "home"));
    }

    @Test
    void aRetainWithTheRetainHandlerAbsentIsRefusedAsUnsupportedUntilItIsRegistered() {
        Result result = Runs.run(produceOnly(), "{\"v\":1,\"op\":\"retain\",\"deadline_ms\":5000,\"start_path\":\".\",\"dry_run\":true,\"policy\":{}}");
        assertThat(result.exit()).isZero();
        assertThat(result.text()).contains("\"reason\":\"unsupported-operation\"");
    }

    @Test
    void aHandlerThatThrowsIsExitSeventyWithNoOutputAndNoStackTrace() {
        OperationHandler boom = StubHandlers.of("produce", request -> {
            throw new IllegalStateException("secret row value at " + String.join("/", "", "home", "someone", "file"));
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> halts = new ArrayList<>();
        int exit = new Launcher(() -> Dispatcher.of(boom), halts::add).run(new ByteArrayInputStream(GOOD.getBytes(StandardCharsets.UTF_8)), out);
        assertThat(exit).isEqualTo(70);
        assertThat(out.toByteArray()).isEmpty();
    }

    @Test
    void aDispatcherThatCannotBeBuiltIsExitSeventy() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = new Launcher(() -> {
            throw new IllegalStateException("two handlers claim produce");
        }, code -> {}).run(new ByteArrayInputStream(GOOD.getBytes(StandardCharsets.UTF_8)), out);
        assertThat(exit).isEqualTo(70);
        assertThat(out.toByteArray()).isEmpty();
    }

    @Test
    void anInputThatCannotBeReadIsExitSixtyFour() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("closed");
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(new Launcher(() -> produceOnly(), code -> {}).run(broken, out)).isEqualTo(64);
        assertThat(out.toByteArray()).isEmpty();
    }

    @Test
    void anOutputThatCannotBeWrittenIsExitSeventy() {
        java.io.OutputStream broken = new java.io.OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("closed");
            }
        };
        int exit = new Launcher(() -> produceOnly(), code -> {}).run(new ByteArrayInputStream(GOOD.getBytes(StandardCharsets.UTF_8)), broken);
        assertThat(exit).isEqualTo(70);
    }

    @Test
    void aRequestOverTheSizeBoundIsUnreadable() {
        String big = "{\"v\":1,\"op\":\"produce\",\"deadline_ms\":5000,\"pad\":\"" + "x".repeat(Launcher.MAX_REQUEST_BYTES) + "\"}";
        assertUnreadable(big);
        String fits = "{\"v\":1,\"op\":\"produce\",\"deadline_ms\":5000,\"pad\":\"" + "x".repeat(Launcher.MAX_REQUEST_BYTES - 100) + "\"}";
        assertThat(Runs.run(produceOnly(), fits).exit()).isZero();
    }

    // ----- the deadline watchdog ---------------------------------------------------------------------------------------

    @Test
    void aHandlerStillRunningAtTheDeadlineIsHaltedWithSeventyFive() throws Exception {
        CountDownLatch halted = new CountDownLatch(1);
        List<Integer> halts = new java.util.concurrent.CopyOnWriteArrayList<>();
        OperationHandler slow = StubHandlers.of("produce", request -> {
            try {
                assertThat(halted.await(30, TimeUnit.SECONDS)).as("the watchdog fired").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Launcher(() -> Dispatcher.of(slow), code -> {
            halts.add(code);
            halted.countDown();
        }).run(new ByteArrayInputStream("{\"v\":1,\"op\":\"produce\",\"deadline_ms\":50,\"start_path\":\".\"}".getBytes(StandardCharsets.UTF_8)), out);
        assertThat(halts).containsExactly(75);
    }

    @Test
    void aRunThatFinishesInTimeIsNeverHaltedAfterwards() throws Exception {
        List<Integer> halts = new java.util.concurrent.CopyOnWriteArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = new Launcher(() -> produceOnly(), halts::add)
                .run(new ByteArrayInputStream("{\"v\":1,\"op\":\"produce\",\"deadline_ms\":1000,\"start_path\":\".\"}".getBytes(StandardCharsets.UTF_8)), out);
        assertThat(exit).isZero();
        Thread.sleep(1600);
        assertThat(halts).as("the watchdog was cancelled when the reply was written").isEmpty();
    }

    @Test
    void theWatchdogDoesNotStartForAnUnreadableRequest() throws Exception {
        List<Integer> halts = new java.util.concurrent.CopyOnWriteArrayList<>();
        new Launcher(() -> produceOnly(), halts::add).run(new ByteArrayInputStream("{\"v\":2,\"deadline_ms\":50}".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
        Thread.sleep(400);
        assertThat(halts).isEmpty();
    }

    @Test
    void theDeadlineIsMeasuredFromTheRequestNotFromAPreviousRun() throws Exception {
        List<Integer> halts = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (int i = 0; i < 3; i++) {
            new Launcher(() -> produceOnly(), halts::add)
                    .run(new ByteArrayInputStream("{\"v\":1,\"op\":\"produce\",\"deadline_ms\":2000,\"start_path\":\".\"}".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
        }
        assertThat(halts).isEmpty();
    }

    @Test
    void theExitCodesAreThePublishedOnes() {
        assertThat(Launcher.EXIT_OK).isZero();
        assertThat(Launcher.EXIT_UNREADABLE).isEqualTo(64);
        assertThat(Launcher.EXIT_INTERNAL).isEqualTo(70);
        assertThat(Launcher.EXIT_DEADLINE).isEqualTo(75);
    }
}
