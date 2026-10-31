package io.github.appcommentfilter.hook;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Selects unique call relationships shared by both supplied KMP builds, not obfuscated names. */
public final class KuaishouSharedSymbols {
    public static final String PAGE = "Lcom/kwai/kmp/comment/mvi/panel/pagelist/KmpCommentFeaturePageList;";
    public static final String BASE = "Lcom/kwai/kmp/pagelist/KMPPageList;";
    public static final String CORE = "Lcom/kwai/kmp/comment/mvi/panel/pagelist/KmpCommentFeaturePageList$CommentCorePageList;";
    private KuaishouSharedSymbols() { }

    public static final class Profile {
        public final DexMethodIndex.Member parser, first, loading, latest, cancel, completion, failure, ownerLoad, locked;
        Profile(DexMethodIndex.Member parser, DexMethodIndex.Member first, DexMethodIndex.Member loading, DexMethodIndex.Member latest,
                DexMethodIndex.Member cancel, DexMethodIndex.Member completion, DexMethodIndex.Member failure,
                DexMethodIndex.Member ownerLoad, DexMethodIndex.Member locked) {
            this.parser = parser; this.first = first; this.loading = loading; this.latest = latest; this.cancel = cancel;
            this.completion = completion; this.failure = failure; this.ownerLoad = ownerLoad; this.locked = locked;
        }
    }

    public static DexMethodIndex.Member parser(List<DexMethodIndex.Member> members) throws NoSuchMethodException {
        return unique(members, member -> {
            String parameter = unaryObjectParameter(member.descriptor);
            if (parameter == null || Modifier.isStatic(member.flags) || !member.descriptor.endsWith(")V")) return false;
            return calls(member, parameter, "getKLocalComments", "()Ljava/util/List;")
                    && calls(member, parameter, "getKGodCommentCount", "()I")
                    && calls(member, parameter, "getKTransparentPayload", "()Ljava/lang/String;")
                    && calls(member, parameter, "getKCommentCorrelationId", "()Ljava/lang/String;");
        }, "comment parser call fingerprint");
    }

    public static Profile resolve(List<DexMethodIndex.Member> page, List<DexMethodIndex.Member> base) throws NoSuchMethodException {
        DexMethodIndex.Member parser = parser(page);
        DexMethodIndex.Member load = named(base, "load", "()V");
        DexMethodIndex.Member loading = called(base, load, BASE, "()Z", "loading getter");
        DexMethodIndex.Member latest = unique(base, m -> !Modifier.isStatic(m.flags)
                && "()Ljava/lang/Object;".equals(m.descriptor), "latest response getter");
        DexMethodIndex.Member first = unique(base, m -> !Modifier.isStatic(m.flags) && "()Z".equals(m.descriptor)
                && calls(m, BASE, latest.name, latest.descriptor), "first page predicate");
        DexMethodIndex.Member release = named(base, "release", "()V"), refresh = named(base, "refresh", "()V");
        Set<String> shared = new HashSet<>();
        for (DexMethodIndex.Call call : release.calls) if (BASE.equals(call.owner) && "()V".equals(call.descriptor))
            if (calls(refresh, call.owner, call.name, call.descriptor)) shared.add(call.name);
        DexMethodIndex.Member cancel = unique(base, m -> shared.contains(m.name) && "()V".equals(m.descriptor), "pager cancellation");
        DexMethodIndex.Member completion = unique(base, m -> !Modifier.isStatic(m.flags)
                && unaryObjectParameter(m.descriptor) != null && m.descriptor.endsWith(")V")
                && calls(m, BASE, first.name, first.descriptor)
                && m.calls.stream().anyMatch(call -> BASE.equals(call.owner)
                    && "(Ljava/lang/Object;Ljava/util/List;)V".equals(call.descriptor)), "pager completion");
        DexMethodIndex.Member failure = unique(base, m -> "(Ljava/lang/Throwable;)V".equals(m.descriptor), "pager failure");
        DexMethodIndex.Member ownerLoad = unique(page, m -> "()V".equals(m.descriptor)
                && (calls(m, CORE, "load", "()V") || calls(m, BASE, "load", "()V")), "comment load action");
        DexMethodIndex.Member locked = called(page, ownerLoad, PAGE, "()Z", "comment login lock");
        return new Profile(parser, first, loading, latest, cancel, completion, failure, ownerLoad, locked);
    }

    public static String unaryObjectParameter(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("(L")) return null;
        int end = descriptor.indexOf(';');
        return end > 2 && end + 1 < descriptor.length() && descriptor.charAt(end + 1) == ')'
                ? descriptor.substring(1, end + 1) : null;
    }
    public static boolean calls(DexMethodIndex.Member member, String owner, String name, String descriptor) {
        for (DexMethodIndex.Call call : member.calls)
            if (owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.descriptor)) return true;
        return false;
    }
    private static DexMethodIndex.Member named(List<DexMethodIndex.Member> members, String name, String descriptor) throws NoSuchMethodException {
        return unique(members, member -> name.equals(member.name) && descriptor.equals(member.descriptor), name + descriptor);
    }
    private static DexMethodIndex.Member called(List<DexMethodIndex.Member> members, DexMethodIndex.Member caller,
                                               String owner, String descriptor, String label) throws NoSuchMethodException {
        return unique(members, member -> !Modifier.isStatic(member.flags) && descriptor.equals(member.descriptor)
                && calls(caller, owner, member.name, member.descriptor), label);
    }
    static DexMethodIndex.Member unique(List<DexMethodIndex.Member> members, Predicate<DexMethodIndex.Member> accept, String label)
            throws NoSuchMethodException {
        List<DexMethodIndex.Member> matches = new ArrayList<>();
        for (DexMethodIndex.Member member : members) if (accept.test(member)) matches.add(member);
        if (matches.size() != 1) throw new NoSuchMethodException("Expected unique " + label + ", found " + matches.size());
        return matches.get(0);
    }
}
