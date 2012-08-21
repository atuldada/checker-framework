package checkers.quals;

/**
 * Specifies the locations to which a {@link DefaultQualifier} annotation applies.
 *
 * @see DefaultQualifier
 */
public enum DefaultLocation {

    /** Apply default annotations to all unannotated types. */
    ALL,

    /** Apply default annotations to all unannotated types except the raw types
     * of local types (local variables, casts, and instanceof). */
    ALL_EXCEPT_LOCALS,

    /** Apply default annotations to all unannotated local types (local
     * variables, casts, and instanceof).
     * TODO: use this instead of the programmatic way.
     */
    // LOCALS,

    /** Apply default annotations to unannotated upper bounds:  both
     * explicit ones in <tt>extends</tt> clauses, and implicit upper bounds
     * when no explicit <tt>extends</tt> or <tt>super</tt> clause is
     * present. */
    // Especially useful for parametrized classes that provide a lot of
    // static methods with the same generic parameters as the class.
    // TODO: Add a test case for this.
    UPPER_BOUNDS;

}
