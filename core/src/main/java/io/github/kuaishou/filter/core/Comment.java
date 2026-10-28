package io.github.kuaishou.filter.core;

/** A local comment snapshot. Location is the displayed region, never an IP address. */
public final class Comment {
    private final String text;
    private final String location;
    private final Gender gender;
    private final boolean reply;

    public Comment(String text, String location, Gender gender, boolean reply) {
        this.text = text == null ? "" : text;
        this.location = location;
        this.gender = gender == null ? Gender.UNKNOWN : gender;
        this.reply = reply;
    }

    public String text() { return text; }
    public String location() { return location; }
    public Gender gender() { return gender; }
    public boolean reply() { return reply; }
}
