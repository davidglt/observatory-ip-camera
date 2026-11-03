package local.termux.observatorycamera;

/**
 * Process-wide camera ownership. At most one component (the single-capture activity or the
 * continuous capture service) may use the camera; a conflicting request is rejected rather than
 * opening a second camera. Ownership is in memory only and is released on every close path.
 */
public final class CameraArbiter {
    public static final String NONE = "none";
    public static final String SINGLE = "single-capture";
    public static final String CONTINUOUS = "continuous";

    private static final CameraArbiter SHARED = new CameraArbiter();

    public static CameraArbiter shared() { return SHARED; }

    private String owner = NONE;

    /** Returns true when owner now holds the camera; false if anyone (including owner itself) already holds it. */
    public synchronized boolean acquire(String requester) {
        if (!owner.equals(NONE)) return false;
        owner = requester;
        return true;
    }

    /** Releases only if requester is the current owner, so a late release cannot free someone else's lock. */
    public synchronized void release(String requester) {
        if (owner.equals(requester)) owner = NONE;
    }

    public synchronized String owner() { return owner; }
}
