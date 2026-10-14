package ai.speckitty.producer.adapter.identity;

import java.util.List;
import java.util.function.Function;

/**
 * The user name: the first non-empty of {@code LOGNAME}, {@code USER}, {@code LNAME}, {@code USERNAME} (the order of Python's
 * {@code getpass.getuser}), then the Java {@code user.name} property. Python's password-database fallback has no Java counterpart
 * without a native call, so with nothing set the answer is an error, never a guess.
 */
final class UserNames {

    private static final List<String> VARIABLES = List.of("LOGNAME", "USER", "LNAME", "USERNAME");

    private UserNames() {}

    static String resolve(Function<String, String> environment, Function<String, String> properties) {
        for (String name : VARIABLES) {
            String value = environment.apply(name);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        String property = properties.apply("user.name");
        if (property != null && !property.isEmpty()) {
            return property;
        }
        throw new IllegalStateException("no user name in LOGNAME, USER, LNAME, USERNAME or user.name");
    }
}
