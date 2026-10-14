package ai.speckitty.producer.adapter.identity;

import ai.speckitty.producer.application.port.NodeIdentity;
import java.util.function.Supplier;

/** The node id of this machine and user, derived in Java to equal the Python {@code generate_node_id()} (OD-NODEID). */
public final class SystemNodeIdentity implements NodeIdentity {

    private final Supplier<String> hostname;
    private final Supplier<String> username;

    /** The live host name (FFM {@code gethostname}) and user name (environment, then {@code user.name}). */
    public SystemNodeIdentity() {
        this(HostName::get, () -> UserNames.resolve(System::getenv, System::getProperty));
    }

    SystemNodeIdentity(Supplier<String> hostname, Supplier<String> username) {
        this.hostname = hostname;
        this.username = username;
    }

    @Override
    public String nodeId() {
        return NodeIds.of(hostname.get(), username.get());
    }
}
