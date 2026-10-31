package io.github.appcommentfilter.hook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class KuaishouFooterBindingsTest {
    private static final String OWNER = "Lrenamed/Wrapper;", VIEW = "Landroid/view/View;", LP = "Landroid/view/ViewGroup$LayoutParams;";
    private static DexMethodIndex.Member member(String name, String descriptor, List<DexMethodIndex.Call> calls, DexMethodIndex.FieldRef... fields) {
        return new DexMethodIndex.Member(OWNER, name, descriptor, 1, calls, Arrays.asList(fields));
    }
    private static DexMethodIndex.FieldRef read(String group) { return new DexMethodIndex.FieldRef(OWNER, group, "Lrenamed/Group;", 0x54); }
    private static List<DexMethodIndex.Member> fixture(String addName) {
        String unary = "(" + VIEW + ")V", binary = "(" + VIEW + LP + ")V";
        return new ArrayList<>(List.of(
                member("l2", "(" + VIEW + ")Z", List.of(), read("footerBag")),
                member("E2", "(" + VIEW + ")Z", List.of(), read("footerBag")),
                member("headerFirst", unary, List.of(new DexMethodIndex.Call(OWNER, "headerHelper", binary))),
                member("headerHelper", binary, List.of(), read("headerBag")),
                member(addName, unary, List.of(new DexMethodIndex.Call(OWNER, "footerHelper", binary))),
                member("footerHelper", binary, List.of(), read("footerBag"))));
    }
    @Test public void renamedFooterEntrySelectedByFooterReadsInsteadOfHeaderSymmetry() throws Exception {
        for (String name : List.of("Q1", "c2", "anotherFutureName")) {
            KuaishouFooterBindings.AddFooter result = KuaishouFooterBindings.addFooter(fixture(name), OWNER, VIEW, LP);
            assertEquals(name, result.method); assertEquals("footerBag", result.group); assertEquals("footerHelper", result.helper);
        }
    }
    @Test public void twoMatchingEntriesAreRejectedRatherThanPickingFirst() throws Exception {
        List<DexMethodIndex.Member> members = fixture("candidateOne");
        members.add(member("candidateTwo", "(" + VIEW + ")V", List.of(new DexMethodIndex.Call(OWNER, "footerHelper", "(" + VIEW + LP + ")V"))));
        try { KuaishouFooterBindings.addFooter(members, OWNER, VIEW, LP); fail(); }
        catch (NoSuchMethodException expected) { }
    }
    @Test public void mismatchedAnchorsCannotSelectHeaderOrWrongGroup() throws Exception {
        List<DexMethodIndex.Member> members = fixture("add");
        members.set(1, member("E2", "(" + VIEW + ")Z", List.of(), read("headerBag")));
        try { KuaishouFooterBindings.addFooter(members, OWNER, VIEW, LP); fail(); }
        catch (NoSuchMethodException expected) { }
    }
    @Test public void fieldWritesOrStaticReadsCannotMasqueradeAsFooterGroupReads() throws Exception {
        for (int opcode : new int[]{0x5b, 0x62}) {
            List<DexMethodIndex.Member> members = fixture("add");
            members.set(5, member("footerHelper", "(" + VIEW + LP + ")V", List.of(),
                    new DexMethodIndex.FieldRef(OWNER, "footerBag", "Lrenamed/Group;", opcode)));
            try { KuaishouFooterBindings.addFooter(members, OWNER, VIEW, LP); fail(); }
            catch (NoSuchMethodException expected) { }
        }
    }
    public static class FakeView { }
    public static class FakeAdapter { }
    public static class NewWrapper extends FakeAdapter {
        public FakeAdapter h;
        public boolean l2(FakeView view) { return false; }
        public boolean E2(FakeView view) { return false; }
    }
    public static class HeaderOnly extends FakeAdapter {
        public FakeAdapter h;
        public boolean m2(FakeView view) { return false; }
        public boolean F2(FakeView view) { return false; }
    }
    public static class Recycler {
        public NewWrapper renamed;
        public HeaderOnly decoy;
    }
    public static class OtherWrapper extends NewWrapper { public FakeAdapter h; }
    public static class AmbiguousRecycler { public NewWrapper one; public OtherWrapper two; }
    @Test public void wrapperComesFromActualFieldTypeWithoutPackageNameAndRejectsHeaderOnly() throws Exception {
        assertEquals(NewWrapper.class, KuaishouFooterBindings.wrapper(Recycler.class, FakeAdapter.class, FakeView.class));
        try { KuaishouFooterBindings.wrapper(AmbiguousRecycler.class, FakeAdapter.class, FakeView.class); fail(); }
        catch (NoSuchFieldException expected) { }
    }
    public interface OldPager { Object j0(); boolean hasMore(); }
    public interface NewPager { Object k0(); boolean hasMore(); }
    public interface AmbiguousPager { Object one(); Object two(); }
    public static class Params { }
    public abstract static class OldComments { public abstract Params Tq(); }
    public abstract static class NewComments { public abstract Params Wq(); }
    @Test public void renamedParamsAndPagerGetterSelectedByExactReturnContract() throws Exception {
        assertEquals("j0", KuaishouFooterBindings.noArgReturn(OldPager.class, Object.class, "pager").getName());
        assertEquals("k0", KuaishouFooterBindings.noArgReturn(NewPager.class, Object.class, "pager").getName());
        assertEquals("Tq", KuaishouFooterBindings.noArgReturn(OldComments.class, Params.class, "params").getName());
        assertEquals("Wq", KuaishouFooterBindings.noArgReturn(NewComments.class, Params.class, "params").getName());
        try { KuaishouFooterBindings.noArgReturn(AmbiguousPager.class, Object.class, "pager"); fail(); }
        catch (NoSuchMethodException expected) { }
    }
}
