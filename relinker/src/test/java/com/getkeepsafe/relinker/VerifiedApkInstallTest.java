package com.getkeepsafe.relinker;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class VerifiedApkInstallTest {
    @Rule public final TemporaryFolder files = new TemporaryFolder();

    private final ApkLibraryInstaller installer = new ApkLibraryInstaller();
    private final ReLinkerInstance instance = new ReLinkerInstance();
    private final String[] abis = {"arm64-v8a"};
    private final String library = "libexample.so";

    private File apk(String name, String entry, byte[] bytes) throws IOException {
        File file = files.newFile(name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(bytes);
        }
        return file;
    }

    @Test public void refreshesStaleCacheFromInstalledSplitAndReusesVerifiedBytes() throws Exception {
        byte[] expected = {4, 5, 6};
        File base = apk("base.apk", "classes.dex", new byte[0]);
        File split = apk("split.apk", "lib/arm64-v8a/" + library, expected);
        File target = files.newFile(library);
        Files.write(target.toPath(), new byte[] {1, 2, 3});
        assertTrue(target.setExecutable(true));
        String[] apks = {base.getPath(), split.getPath()};
        installer.installFromApks(apks, abis, library, target, instance);
        assertArrayEquals(expected, Files.readAllBytes(target.toPath()));
        assertTrue(target.setLastModified(12345678000L));
        long stamp = target.lastModified();
        installer.installFromApks(apks, abis, library, target, instance);
        assertEquals(stamp, target.lastModified());
    }

    @Test public void reportsOnlyLiteralLibraryNameMatches() throws Exception {
        File apk = apk("base.apk", "lib/x86/libexampleXso", new byte[0]);
        try {
            installer.installFromApks(new String[] {apk.getPath()}, abis, library, files.newFile(), instance);
            fail("expected missing library");
        } catch (MissingLibraryException expected) {
            assertTrue(expected.getMessage().endsWith("but only found: []."));
        }
    }

    @Test public void reportsInstallFailureAndCleansStagingFiles() throws Exception {
        File apk = apk("base.apk", "lib/arm64-v8a/" + library, new byte[] {1});
        File target = files.newFolder(library);
        File sentinel = new File(target, "keep");
        assertTrue(sentinel.createNewFile());
        try {
            installer.installFromApks(new String[] {apk.getPath()}, abis, library, target, instance);
            fail("expected extraction failure");
        } catch (UnsatisfiedLinkError expected) {
            assertTrue(expected.getCause() instanceof IOException);
        }
        assertTrue(sentinel.isFile());
        assertEquals(2, files.getRoot().list().length);
    }

    @Test public void confinesVersionAndMappedNameToTheLibraryDirectory() throws Exception {
        ReLinkerInstance confined = new ReLinkerInstance() {
            @Override protected File getWorkaroundLibDir(android.content.Context ignored) {
                return files.getRoot();
            }
        };
        for (String version : new String[] {"../version", "v/sub", "v\\sub"}) {
            try {
                confined.getWorkaroundLibFile(null, "libexample.so", version);
                fail("expected invalid version");
            } catch (IllegalArgumentException expected) {}
        }
        assertEquals(new File(files.getRoot(), library + ".2.0"),
                confined.getWorkaroundLibFile(null, library, "2.0"));
        Files.createSymbolicLink(new File(files.getRoot(), library).toPath(), files.newFile().toPath());
        try {
            confined.getWorkaroundLibFile(null, library, null);
            fail("expected invalid symbolic link");
        } catch (IllegalArgumentException expected) {}
    }
}
