package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.RuleEngine;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class DouyinKmpBindingsTest {
    enum RequestKind { INITIAL, REFRESH, LOAD_MORE }
    public static class Response extends DouyinCommentReaderTest.Response { }
    public static class RenamedDataSource {
        public Response received;
        public RequestKind kind;
        public void renamedAccept(RequestKind kind, Response response) { this.kind = kind; received = response; }
        public void wrongOrder(Response response, RequestKind kind) { }
        public void wrongMode(int kind, Response response) { }
        public static void staticDecoy(RequestKind kind, Response response) { }
    }
    public static class ReplySource { public void reply(Response response, boolean flag) { } }
    public static class StableVmFixture {
        public RenamedDataSource renamedGetter() { return null; }
        public RenamedDataSource anotherGetterReturningSameSource() { return null; }
        public ReplySource replyGetter() { return null; }
        public String otherGetter() { return ""; }
    }
    public static class SecondDataSource { public void accept(RequestKind kind, Response response) { } }
    public static class AmbiguousVm {
        public RenamedDataSource first() { return null; }
        public SecondDataSource second() { return null; }
    }
    public static class MissingVm { public ReplySource onlyReplies() { return null; } }
    private RuleEngine rules() {
        return RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"blacklist\":[{\"id\":\"r\",\"keywords\":[\"广告\"]}]}");
    }
    @Test public void locatesRenamedSourceAndTypedEnumBoundaryWithoutNameGuessing() throws Exception {
        DouyinConsumerBindings.KmpConsumer binding = DouyinConsumerBindings.kmp(StableVmFixture.class, Response.class);
        assertEquals(RenamedDataSource.class, binding.method.getDeclaringClass());
        assertEquals("renamedAccept", binding.method.getName()); assertEquals(1, binding.responseArgument);
        assertTrue(binding.method.getParameterTypes()[0].isEnum());
    }
    @Test public void absentOrAmbiguousKmpContractIsRejected() throws Exception {
        for (Class<?> type : List.of(MissingVm.class, AmbiguousVm.class)) {
            try { DouyinConsumerBindings.kmp(type, Response.class); fail("Must reject a present incompatible KMP contract"); }
            catch (NoSuchMethodException expected) { }
        }
    }
    @Test public void firstRefreshAndMoreKeepEnumAndOriginalMetadataWhileReplacingOnlyResponseArgument() throws Exception {
        DouyinConsumerBindings.KmpConsumer binding = DouyinConsumerBindings.kmp(StableVmFixture.class, Response.class);
        DouyinResponseCopy copies = new DouyinResponseCopy(Response.class);
        DouyinCommentReader reader = new DouyinCommentReader(DouyinCommentReaderTest.Row.class, DouyinCommentReaderTest.User.class);
        for (RequestKind kind : RequestKind.values()) {
            Response original = new Response(); DouyinCommentReaderTest.Row hidden = new DouyinCommentReaderTest.Row();
            DouyinCommentReaderTest.Row visible = new DouyinCommentReaderTest.Row(); visible.text = "保留";
            DouyinCommentReaderTest.Row reply = new DouyinCommentReaderTest.Row(); reply.replyId = "private-parent";
            original.items = Arrays.asList(hidden, visible, reply);
            Object[] arguments = {kind, original};
            copies.filterArgument(arguments, binding.responseArgument, reader, rules());
            RenamedDataSource source = new RenamedDataSource(); binding.method.invoke(source, arguments);
            assertSame(kind, arguments[0]); assertSame(kind, source.kind);
            assertNotSame(original, source.received); assertEquals(List.of(visible, reply), source.received.items);
            assertEquals(3, original.items.size()); assertEquals(original.cursor, source.received.cursor);
            assertEquals(original.total, source.received.total); assertEquals(original.hasMore, source.received.hasMore);
            assertSame(original.config, source.received.config);
        }
    }
    @Test public void disabledOrNoMatchLeavesResponseArgumentAndSourceListIdentical() throws Exception {
        DouyinConsumerBindings.KmpConsumer binding = DouyinConsumerBindings.kmp(StableVmFixture.class, Response.class);
        DouyinResponseCopy copies = new DouyinResponseCopy(Response.class);
        DouyinCommentReader reader = new DouyinCommentReader(DouyinCommentReaderTest.Row.class, DouyinCommentReaderTest.User.class);
        Response original = new Response(); DouyinCommentReaderTest.Row visible = new DouyinCommentReaderTest.Row(); visible.text = "保留";
        original.items = List.of(visible); Object[] args = {RequestKind.INITIAL, original};
        copies.filterArgument(args, binding.responseArgument, reader, RuleEngine.compileJson(RuleEngine.defaultJson()));
        assertSame(original, args[1]); copies.filterArgument(args, binding.responseArgument, reader, rules());
        assertSame(original, args[1]); assertEquals(List.of(visible), original.items);
    }
}
