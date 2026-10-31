package io.github.appcommentfilter.hook;

import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class KuaishouSharedSymbolsTest {
    private DexMethodIndex.Member method(String owner, String name, String descriptor, DexMethodIndex.Call... calls) {
        return new DexMethodIndex.Member(owner, name, descriptor, 1, Arrays.asList(calls), Collections.emptyList());
    }
    private DexMethodIndex.Call call(String owner, String name, String descriptor) { return new DexMethodIndex.Call(owner, name, descriptor); }
    private DexMethodIndex.Member parser(String name, String response) {
        return method(KuaishouSharedSymbols.PAGE, name, "(" + response + ")V",
                call(response, "getKLocalComments", "()Ljava/util/List;"),
                call(response, "getKGodCommentCount", "()I"),
                call(response, "getKTransparentPayload", "()Ljava/lang/String;"),
                call(response, "getKCommentCorrelationId", "()Ljava/lang/String;"));
    }
    @Test public void responsePackageAndParserNameCanChangeWithoutAddingAnAlias() throws Exception {
        for (String name : List.of("w1", "x1", "futureRenamedParser")) {
            DexMethodIndex.Member member = parser(name, "Ldifferent/Response;");
            assertSame(member, KuaishouSharedSymbols.parser(List.of(member)));
        }
    }
    @Test public void similarMethodWithWrongReturnTypeIsNotTheParser() throws Exception {
        DexMethodIndex.Member real = parser("x1", "Lnew/Response;");
        DexMethodIndex.Member decoy = new DexMethodIndex.Member(real.owner, "w1", "(Lnew/Response;)Lnew/List;", 1, real.calls, real.fields);
        assertSame(real, KuaishouSharedSymbols.parser(List.of(decoy, real)));
    }
    @Test public void signatureOrGetterNamesWithoutTheSameResponseOwnerAreInsufficient() {
        DexMethodIndex.Member member = parser("decoy", "Lnew/Response;");
        DexMethodIndex.Member wrong = new DexMethodIndex.Member(member.owner, member.name, "(Lother/Response;)V", 1, member.calls, member.fields);
        try { KuaishouSharedSymbols.parser(List.of(wrong)); fail(); }
        catch (NoSuchMethodException expected) { }
    }
    @Test public void ambiguityNeverSelectsFirstMatch() {
        try { KuaishouSharedSymbols.parser(List.of(parser("a", "Lmodel/Response;"), parser("b", "Lmodel/Response;"))); fail(); }
        catch (NoSuchMethodException expected) { assertTrue(expected.getMessage().contains("found 2")); }
    }
    @Test public void missingOptionalPagingDoesNotRemoveAnIdentifiableCoreParser() throws Exception {
        List<DexMethodIndex.Member> page = List.of(parser("any", "Lmodel/Response;"));
        assertNotNull(KuaishouSharedSymbols.parser(page));
        try { KuaishouSharedSymbols.resolve(page, List.of()); fail(); }
        catch (NoSuchMethodException expected) { }
    }
    @Test public void changedPagingNamesResolveThroughTheSameCallRelations() throws Exception {
        String p = KuaishouSharedSymbols.PAGE, b = KuaishouSharedSymbols.BASE;
        DexMethodIndex.Member parser = parser("future", "Lmodel/Response;");
        List<DexMethodIndex.Member> page = List.of(parser,
                method(p, "trigger", "()V", call(KuaishouSharedSymbols.CORE, "load", "()V"), call(p, "loginGate", "()Z")),
                method(p, "loginGate", "()Z"));
        List<DexMethodIndex.Member> base = List.of(
                method(b, "load", "()V", call(b, "busy", "()Z")),
                method(b, "isInitialRenamed", "()Z", call(b, "lastPage", "()Ljava/lang/Object;")),
                method(b, "busy", "()Z"), method(b, "lastPage", "()Ljava/lang/Object;"),
                method(b, "release", "()V", call(b, "stopWork", "()V")),
                method(b, "refresh", "()V", call(b, "stopWork", "()V")),
                method(b, "stopWork", "()V"), method(b, "failureRenamed", "(Ljava/lang/Throwable;)V"),
                method(b, "doneRenamed", "(Lcompletion/Renamed;)V", call(b, "isInitialRenamed", "()Z"),
                        call(b, "parseRenamed", "(Ljava/lang/Object;Ljava/util/List;)V")));
        KuaishouSharedSymbols.Profile result = KuaishouSharedSymbols.resolve(page, base);
        assertEquals("busy", result.loading.name); assertEquals("lastPage", result.latest.name);
        assertEquals("stopWork", result.cancel.name); assertEquals("doneRenamed", result.completion.name);
        assertEquals("failureRenamed", result.failure.name); assertEquals("trigger", result.ownerLoad.name);
        assertEquals("loginGate", result.locked.name);
        assertEquals("isInitialRenamed", result.first.name);
    }
    public static final class WithModel { CommentListFilterTest.Item renamed; }
    public static final class ErasedResponse { public List<?> getKRootComments() { return null; } }
    public static final class GenericResponse { public List<CommentListFilterTest.Item> getKRootComments() { return null; } }
    @Test public void modelUsesReadableContractWithAndWithoutGenericMetadata() throws Exception {
        assertEquals(CommentListFilterTest.Item.class, KuaishouBindings.commentType(WithModel.class, ErasedResponse.class));
        assertEquals(CommentListFilterTest.Item.class, KuaishouBindings.commentType(Object.class, GenericResponse.class));
    }
}
