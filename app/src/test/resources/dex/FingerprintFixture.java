package fixture.dex;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.function.Supplier;

/** Source of fingerprint.dex. Rebuild instructions are in README.md beside it. */
public class FingerprintFixture {
    private Object group;
    public static int staticValue;
    public int value;
    public interface Response {
        List<?> getKLocalComments();
        int getKGodCommentCount();
        String getKTransparentPayload();
        String getKCommentCorrelationId();
    }
    public void parser(Response response) {
        response.getKLocalComments(); response.getKGodCommentCount();
        response.getKTransparentPayload(); response.getKCommentCorrelationId();
    }
    public Object readGroup() { return group; }
    public void writeGroup(Object value) { group = value; }
    public int readAndWriteStatic(int input) { staticValue = input; return staticValue + value; }
    public static int[] arrayPayload() { return new int[]{0x00710071, 0, 0x00780078, 0, 0x006e006e, 0}; }
    public static long wideLiteral() { return 0x0000007100000071L; }
    public int packed(int input) {
        switch (input) {
            case 1: return value;
            case 2: return value + 1;
            case 3: return value + 2;
            case 4: return value + 3;
            case 5: return value + 4;
            default: return -1;
        }
    }
    public int sparse(int input) {
        switch (input) {
            case 1: return value;
            case 1000: return value + 1;
            case 1000000: return value + 2;
            default: return -1;
        }
    }
    public void overloadCalls() { consume(1); consume("text"); }
    private static void consume(int value) { }
    private static void consume(String value) { }
    public static Object polymorphic(MethodHandle handle, Object input) throws Throwable { return handle.invokeExact(input); }
    public static Supplier<Object> dynamic(Object input) { return () -> input; }
}
