package io.github.appcommentfilter.hook;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class DouyinConsumerBindingsTest {
    public static class Request { }
    public static class OtherRequest { }
    public static class Response extends DouyinCommentReaderTest.Response { }
    public static class RenamedConsumer {
        public final Request owner = new Request();
        public Response received;
        public boolean refresh;
        public void renamedEntry(Response response, boolean refresh) { received = response; this.refresh = refresh; }
        public void renamedUiProjection(Response response, boolean refresh) { received = response; this.refresh = refresh; }
        public void unrelated(Object payload, boolean refresh) { }
        public static void staticNoise(Response payload, boolean refresh) { }
    }
    public static class Decoy {
        public final OtherRequest owner = new OtherRequest();
        public void resemblesConsumer(Response response, boolean refresh) { }
    }
    public static class Helper {
        public RenamedConsumer renamedFactory() { return null; }
        public RenamedConsumer repeatedFactory() { return null; }
        public Decoy unrelatedFactory() { return null; }
    }
    public static class SecondConsumer {
        public final Request owner = new Request();
        public void accept(Response response, boolean refresh) { }
    }
    public static class AmbiguousHelper extends Helper {
        public RenamedConsumer first;
        public SecondConsumer second;
    }
    public static class MissingHelper {
        public Decoy decoy;
    }
    public static class Legacy {
        public Response received;
        public void obfuscatedHandle(Response response) { received = response; }
        public void bridgeLike(Object response) { }
        public void wrongParameters(Response response, int ignored) { }
    }
    public static class Envelope {
        public int state;
        public Object data;
    }
    private io.github.appcommentfilter.core.RuleEngine rules() {
        return io.github.appcommentfilter.core.RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"blacklist\":[{\"id\":\"r\",\"keywords\":[\"广告\"]}]}");
    }
    @Test public void resolvesRenamedMainConsumerOnceAndExcludesOtherRequestAndOverloads() throws Exception {
        List<Method> methods = DouyinConsumerBindings.modern(Helper.class, Request.class, Response.class);
        assertEquals(2, methods.size());
        for (Method method : methods) assertEquals(RenamedConsumer.class, method.getDeclaringClass());
    }
    @Test public void ambiguousOrMissingOwnerCannotInstallAnArbitraryConsumer() throws Exception {
        for (Class<?> helper : List.of(AmbiguousHelper.class, MissingHelper.class)) {
            try { DouyinConsumerBindings.modern(helper, Request.class, Response.class); fail("Must refuse ambiguous or absent ownership"); }
            catch (NoSuchMethodException expected) { }
        }
    }
    @Test public void wrapperBypassesOldResponseDispatchButConcreteConsumerCanFilterBothFirstAndMore() throws Exception {
        Response original = new Response();
        DouyinCommentReaderTest.Row hidden = new DouyinCommentReaderTest.Row();
        DouyinCommentReaderTest.Row visible = new DouyinCommentReaderTest.Row(); visible.text = "保留";
        original.items = Arrays.asList(hidden, visible);
        Envelope envelope = new Envelope(); envelope.data = original;
        DouyinResponseCopy copies = new DouyinResponseCopy(Response.class);
        assertNull(copies.items(envelope)); // The previous hook returned here, before parsing any comment.
        DouyinCommentReader reader = new DouyinCommentReader(DouyinCommentReaderTest.Row.class, DouyinCommentReaderTest.User.class);
        List<Method> methods = DouyinConsumerBindings.modern(Helper.class, Request.class, Response.class);
        for (boolean refresh : new boolean[]{true, false}) {
            RenamedConsumer receiver = new RenamedConsumer();
            Object filtered = copies.withItems(original, reader.filter(copies.items(original), rules()));
            methods.get(0).invoke(receiver, filtered, refresh);
            assertEquals(List.of(visible), receiver.received.items);
            assertEquals(refresh, receiver.refresh);
            assertEquals(original.cursor, receiver.received.cursor);
            assertEquals(original.total, receiver.received.total);
            assertSame(original, envelope.data); assertEquals(2, original.items.size());
        }
    }
    @Test public void legacyResolutionUsesConcreteResponseTypeAndNotTheBridgeName() throws Exception {
        List<Method> methods = DouyinConsumerBindings.legacy(Legacy.class, Response.class);
        assertEquals(1, methods.size());
        Legacy receiver = new Legacy(); Response response = new Response(); methods.get(0).invoke(receiver, response);
        assertSame(response, receiver.received);
    }
}
