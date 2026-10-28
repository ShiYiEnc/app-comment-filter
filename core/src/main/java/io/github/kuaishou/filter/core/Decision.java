package io.github.kuaishou.filter.core;

/** Contains no original comment content, making it suitable for aggregate diagnostics. */
public final class Decision {
    private final boolean hidden;
    private final String reason;
    private final String ruleId;

    Decision(boolean hidden, String reason, String ruleId) {
        this.hidden = hidden;
        this.reason = reason;
        this.ruleId = ruleId;
    }

    public boolean hidden() { return hidden; }
    public String reason() { return reason; }
    public String ruleId() { return ruleId; }
}
