package com.slg.exclusivearenas;

public enum JoinPolicy {
    /** Only members of the host's party may join. */
    PARTY,
    /** Players must present a join code via /ea join <code>. */
    CODE
}
