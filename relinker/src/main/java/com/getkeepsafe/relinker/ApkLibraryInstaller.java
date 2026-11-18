/*
 * Copyright 2015 - 2016 KeepSafe Software, Inc.
 * Modified by RalfNick to verify cached APK entries and publish complete extractions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.getkeepsafe.relinker;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.TreeSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ApkLibraryInstaller implements ReLinker.LibraryInstaller {
    private static final int MAX_TRIES = 5;
    private static final int COPY_BUFFER_SIZE = 4096;

    private String[] sourceDirectories(final Context context) {
        final ApplicationInfo appInfo = context.getApplicationInfo();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            appInfo.splitSourceDirs != null &&
            appInfo.splitSourceDirs.length != 0) {
            String[] apks = new String[appInfo.splitSourceDirs.length + 1];
            apks[0] = appInfo.sourceDir;
            System.arraycopy(appInfo.splitSourceDirs, 0, apks, 1, appInfo.splitSourceDirs.length);
            return apks;
        } else {
            return new String[] { appInfo.sourceDir };
        }
    }

    private static class ZipFileInZipEntry {
        public ZipFile zipFile;
        public ZipEntry zipEntry;

        public ZipFileInZipEntry(ZipFile zipFile, ZipEntry zipEntry) {
            this.zipFile = zipFile;
            this.zipEntry = zipEntry;
        }
    }

    private ZipFileInZipEntry findAPKWithLibrary(final String[] apks,
                                                 final String[] abis,
                                                 final String mappedLibraryName,
                                                 final ReLinkerInstance instance) {

        ZipFileInZipEntry best = null;
        int bestRank = abis.length;
        try {
            for (String sourceDir : apks) {
                ZipFile candidate = openApk(sourceDir);
                if (candidate == null) {
                    continue;
                }
                try {
                    for (int rank = 0; rank < bestRank; rank++) {
                        String name = "lib/" + abis[rank] + "/" + mappedLibraryName;
                        instance.log("Looking for %s in APK %s...", name, sourceDir);
                        ZipEntry entry = candidate.getEntry(name);
                        if (entry != null) {
                            if (best != null) {
                                closeSilently(best.zipFile);
                            }
                            best = new ZipFileInZipEntry(candidate, entry);
                            bestRank = rank;
                            break;
                        }
                    }
                } finally {
                    if (best == null || best.zipFile != candidate) {
                        closeSilently(candidate);
                    }
                }
                if (bestRank == 0) {
                    break;
                }
            }
            return best;
        } catch (RuntimeException | Error failure) {
            if (best != null) {
                closeSilently(best.zipFile);
            }
            throw failure;
        }
    }

    private ZipFile openApk(String sourceDir) {
        for (int attempt = 0; attempt < MAX_TRIES; attempt++) {
            try {
                return new ZipFile(sourceDir);
            } catch (IOException ignored) {
                // Keep looking in the other installed splits if this APK remains unreadable.
            }
        }
        return null;
    }

    // Loop over all APK's again in order to detect which ABI's are actually supported.
    // This second loop is more expensive than trying to find a specific ABI, so it should
    // only be ran when no matching libraries are found. This should keep the overhead of
    // the happy path to a minimum.
    private String[] getSupportedABIs(String[] apks, String mappedLibraryName) {
        Pattern pattern = Pattern.compile("lib/([^/]+)/" + Pattern.quote(mappedLibraryName));
        Set<String> supportedABIs = new TreeSet<String>();
        for (String sourceDir : apks) {
            ZipFile zipFile = null;
            try {
                zipFile = new ZipFile(sourceDir);
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    Matcher match = pattern.matcher(entries.nextElement().getName());
                    if (match.matches()) {
                        supportedABIs.add(match.group(1));
                    }
                }
            } catch (IOException ignored) {
                // Another installed split can still contain the library.
            } finally {
                if (zipFile != null) {
                    try {
                        zipFile.close();
                    } catch (IOException ignored) {}
                }
            }
        }
        return supportedABIs.toArray(new String[supportedABIs.size()]);
    }

    /**
     * Attempts to unpack the given library to the given destination. Implements retry logic for
     * IO operations to ensure they succeed.
     *
     * @param context {@link Context} to describe the location of the installed APK file
     * @param mappedLibraryName The mapped name of the library file to load
     */
    @Override
    public void installLibrary(final Context context,
                               final String[] abis,
                               final String mappedLibraryName,
                               final File destination,
                               final ReLinkerInstance instance) {
        installFromApks(sourceDirectories(context), abis, mappedLibraryName, destination, instance);
    }

    void installFromApks(String[] apks, String[] abis, String name, File destination,
                         ReLinkerInstance instance) {
        ZipFileInZipEntry found = findAPKWithLibrary(apks, abis, name, instance);
        if (found == null) {
            throw new MissingLibraryException(name, abis, getSupportedABIs(apks, name));
        }
        try {
            if (!instance.force && matchesEntry(destination, found.zipEntry)) {
                return;
            }
            IOException failure = null;
            for (int attempt = 0; attempt < MAX_TRIES; attempt++) {
                try {
                    extract(found, destination);
                    return;
                } catch (IOException e) {
                    failure = e;
                }
            }
            UnsatisfiedLinkError error = new UnsatisfiedLinkError("Could not install " + name);
            error.initCause(failure);
            throw error;
        } finally {
            try {
                found.zipFile.close();
            } catch (IOException ignored) {}
        }
    }

    private boolean matchesEntry(File file, ZipEntry entry) {
        if (!file.isFile() || !file.canExecute() || file.length() != entry.getSize()) {
            return false;
        }
        CRC32 crc = new CRC32();
        CheckedInputStream input = null;
        try {
            input = new CheckedInputStream(new FileInputStream(file), crc);
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            while (input.read(buffer) != -1) {
                // CheckedInputStream updates the CRC on each read.
            }
            return crc.getValue() == entry.getCrc();
        } catch (IOException ignored) {
            return false;
        } finally {
            closeSilently(input);
        }
    }

    private void extract(ZipFileInZipEntry found, File destination) throws IOException {
        File temp = File.createTempFile("relinker-", ".tmp", destination.getAbsoluteFile().getParentFile());
        try {
            CRC32 crc = new CRC32();
            InputStream input = new CheckedInputStream(found.zipFile.getInputStream(found.zipEntry), crc);
            try {
                FileOutputStream output = new FileOutputStream(temp);
                try {
                    long written = copy(input, output);
                    if (written != found.zipEntry.getSize() || crc.getValue() != found.zipEntry.getCrc()) {
                        throw new IOException("Incomplete APK entry: " + found.zipEntry.getName());
                    }
                    output.getFD().sync();
                } finally {
                    output.close();
                }
            } finally {
                input.close();
            }
            if (!temp.setExecutable(true, true) || !temp.setWritable(false, false)
                    || !temp.renameTo(destination)) {
                throw new IOException("Could not publish library: " + destination);
            }
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    /**
     * Copies all data from an {@link InputStream} to an {@link OutputStream}.
     *
     * @param in The stream to read from.
     * @param out The stream to write to.
     * @throws IOException when a stream operation fails.
     * @return The actual number of bytes copied
     */
    private long copy(InputStream in, OutputStream out) throws IOException {
        long copied = 0;
        byte[] buf = new byte[COPY_BUFFER_SIZE];
        while (true) {
            int read = in.read(buf);
            if (read == -1) {
                break;
            }
            out.write(buf, 0, read);
            copied += read;
        }
        out.flush();
        return copied;
    }

    /**
     * Closes a {@link Closeable} silently (without throwing or handling any exceptions)
     * @param closeable {@link Closeable} to close
     */
    private void closeSilently(final Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException ignored) {}
    }
}
