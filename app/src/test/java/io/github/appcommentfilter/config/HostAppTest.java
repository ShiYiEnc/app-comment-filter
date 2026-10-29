package io.github.appcommentfilter.config;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostAppTest {
    @Test public void everyProfileHasAUniquePackage() {
        java.util.Set<String> packages = new java.util.HashSet<>();
        for (HostApp app : HostApp.values()) {
            assertTrue(packages.add(app.packageName));
            assertSame(app, HostApp.fromPackage(app.packageName));
        }
        assertEquals(5, packages.size());
    }

    @Test public void callerCanOnlyReadItsOwnApplication() {
        for (HostApp target : HostApp.values()) {
            assertFalse(target.allowsPackages(null));
            assertFalse(target.allowsPackages(new String[0]));
            assertFalse(target.allowsPackages(new String[]{"unrelated.app"}));
            for (HostApp caller : HostApp.values()) {
                assertEquals(target == caller, target.allowsPackages(new String[]{caller.packageName}));
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownPackageDoesNotFallBackToKuaishou() {
        HostApp.fromPackage("unrelated.app");
    }
}
