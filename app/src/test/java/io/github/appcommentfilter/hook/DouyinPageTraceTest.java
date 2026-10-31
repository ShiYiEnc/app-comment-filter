package io.github.appcommentfilter.hook;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class DouyinPageTraceTest {
    public static class Response { public boolean hasMore = true; public long cursor = 789123456789L; }
    enum Kind { LOAD_MORE }
    @Test public void logsMetadataAndCompletionWithoutCursorOrRowValues() throws Exception {
        List<String> logs = new ArrayList<>(); Object source = new Object();
        DouyinPageTrace trace = new DouyinPageTrace(Response.class, logs::add);
        Response response = new Response();
        trace.consumed(source, Kind.LOAD_MORE, response, List.of("private-comment"), List.of());
        trace.completed(source, List.of());
        trace.consumed(source, Kind.LOAD_MORE, response, List.of("private-comment"), List.of());
        String output = String.join("\n", logs);
        assertTrue(output.contains("input=1 visible=0 hasMore=true cursorChanged=true"));
        assertTrue(output.contains("completedRows=0")); assertTrue(output.contains("cursorChanged=false"));
        assertFalse(output.contains("789123456789")); assertFalse(output.contains("private-comment"));
    }
    @Test public void limitsLogsForLongSessions() throws Exception {
        List<String> logs = new ArrayList<>(); Object source = new Object();
        DouyinPageTrace trace = new DouyinPageTrace(Response.class, logs::add);
        Response response = new Response();
        for (int i = 0; i < 10000; i++) {
            response.cursor++; trace.consumed(source, Kind.LOAD_MORE, response, List.of(), List.of());
            trace.completed(source, List.of());
        }
        assertTrue(logs.size() <= 56);
    }
}
