package ai.speckitty.producer.domain.protocol;

import java.util.Map;

/**
 * A {@code produce} request: the persisted row and the call context, passed through uninterpreted.
 *
 * @param version the protocol version, 1
 * @param row the persisted authoritative row
 * @param slot the seam slot
 * @param seamArgs the seam's keyword arguments, as given
 * @param startPath the call site's path; the producer resolves the canonical root from it
 * @param deadlineMs the client's effective timeout in whole milliseconds, at least 1
 */
public record ProduceRequest(
        int version, Map<String, Object> row, Slot slot, Map<String, Object> seamArgs, String startPath, int deadlineMs)
        implements Request {

    public ProduceRequest {
        throw new UnsupportedOperationException("red stub");
    }
}
