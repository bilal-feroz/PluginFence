package com.pluginfence.bootstrap;

/**
 * Outcome of a policy evaluation.
 * <ul>
 *   <li>{@link #ALLOW} - the operation proceeds.</li>
 *   <li>{@link #MONITOR} - the operation proceeds and is recorded, but no policy was applied
 *       (for example the control plane is not yet available).</li>
 *   <li>{@link #ASK} - the operation is prevented now; the user is offered Allow Once / Always Allow.</li>
 *   <li>{@link #BLOCK} - the operation is prevented.</li>
 * </ul>
 */
public enum Verdict {
    ALLOW, MONITOR, ASK, BLOCK;

    /** Whether the hook must prevent the original operation from executing. */
    public boolean prevents() {
        return this == ASK || this == BLOCK;
    }
}
