package ai.speckitty.producer.adapter.schema;

/** The contract cannot be used: absent, malformed, empty, or inconsistent. A validator is never built from a broken contract. */
public final class ContractException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public ContractException(String message) {
        super(message);
    }

    public ContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
