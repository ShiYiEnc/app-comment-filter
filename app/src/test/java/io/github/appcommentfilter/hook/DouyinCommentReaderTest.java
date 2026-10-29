package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.Gender;
import io.github.appcommentfilter.core.RuleEngine;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class DouyinCommentReaderTest {
    public static class User { public int gender; }
    public static class Row {
        public String text = "广告", ipLabel = "广东", replyId = "0";
        public User user, commentUser;
        public Integer level = 1;
        public int commentType = 1;
    }
    public static class BaseResponse { private final String token = "server-token"; }
    public static class Response extends BaseResponse {
        public List<?> items;
        public long cursor = 987, total = 1000;
        public boolean hasMore = true;
        public final Object config = new Object();
    }
    private DouyinCommentReader reader() throws Exception { return new DouyinCommentReader(Row.class, User.class); }
    private RuleEngine rules(String mode, String field) {
        return RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"mode\":\"" + mode
                + "\",\"blacklist\":[{\"id\":\"b\"," + field + "}],\"whitelist\":[{\"id\":\"w\"," + field + "}]}");
    }
    @Test public void preservesOriginalAndNestedReplies() throws Exception {
        Row root = new Row(); Row nested = new Row(); nested.replyId = "123";
        List<?> original = Collections.unmodifiableList(Arrays.asList(root, nested, null, new Object()));
        List<?> result = reader().filter(original, rules("BLACKLIST", "\"keywords\":[\"广告\"]"));
        assertEquals(3, result.size()); assertSame(nested, result.get(0)); assertEquals(4, original.size());
    }
    @Test public void levelAndReplyTypesExcludedEvenWhenParentMissing() throws Exception {
        for (int type : new int[]{2, 305, 306, 999}) {
            Row row = new Row(); row.commentType = type;
            assertNull(reader().read(row));
        }
        Row deep = new Row(); deep.level = 2; deep.replyId = null;
        assertNull(reader().read(deep));
    }
    @Test public void commentUserOverridesUserAndServerGenderIsMapped() throws Exception {
        Row row = new Row(); row.user = new User(); row.user.gender = 1;
        assertEquals(Gender.M, reader().read(row).gender());
        row.commentUser = new User(); row.commentUser.gender = 2;
        assertEquals(Gender.F, reader().read(row).gender());
        row.commentUser.gender = 9;
        assertEquals(Gender.UNKNOWN, reader().read(row).gender());
        row.commentUser = null; row.user = null;
        assertEquals(Gender.UNKNOWN, reader().read(row).gender());
    }
    @Test public void whitelistFiltersOnlyRootsAndSupportsRegion() throws Exception {
        Row match = new Row(); Row miss = new Row(); miss.ipLabel = "北京";
        Row reply = new Row(); reply.replyId = "777";
        List<?> result = reader().filter(Arrays.asList(match, miss, reply), rules("WHITELIST_ONLY", "\"locations\":[\"广东\"]"));
        assertEquals(Arrays.asList(match, reply), result);
    }
    @Test public void disabledAndEmptyWhitelistReturnSameList() throws Exception {
        List<?> original = Collections.singletonList(new Row());
        assertSame(original, reader().filter(original, RuleEngine.compileJson(RuleEngine.defaultJson())));
        assertSame(original, reader().filter(original, RuleEngine.compileJson("{\"schemaVersion\":1,\"enabled\":true,\"mode\":\"WHITELIST_ONLY\",\"whitelist\":[]}")));
    }
    @Test public void responseCopyKeepsCursorTotalAndInheritedMetadataWithoutTouchingOriginal() throws Exception {
        Response response = new Response(); response.items = Arrays.asList(new Row(), new Row());
        List<?> replacement = Collections.singletonList(response.items.get(1));
        DouyinResponseCopy copier = new DouyinResponseCopy(Response.class);
        Response result = (Response) copier.withItems(response, replacement);
        assertNotSame(response, result); assertSame(replacement, result.items);
        assertEquals(2, response.items.size()); assertEquals(response.cursor, result.cursor);
        assertEquals(response.total, result.total); assertEquals(response.hasMore, result.hasMore);
        assertSame(response.config, result.config);
        java.lang.reflect.Field token = BaseResponse.class.getDeclaredField("token"); token.setAccessible(true);
        assertEquals(token.get(response), token.get(result));
    }
}
