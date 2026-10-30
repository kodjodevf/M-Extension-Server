package android.webkit;

/**
 * This class provides more specific information about why the render process
 * exited. The application may use this to decide how to handle the situation.
 *
 * Implemented here because the stub's constructor throws.
 */
public abstract class RenderProcessGoneDetail {
    /**
     * Indicates whether the render process was observed to crash, or whether
     * it was killed by the system.
     */
    public abstract boolean didCrash();

    /** Returns the renderer priority that was set at the time that the renderer exited. */
    public abstract int rendererPriorityAtExit();
}
