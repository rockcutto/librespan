package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;
import android.system.Os;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumentation coverage for the real FileProvider boundary.
 *
 * <p>The test intentionally goes through {@link FileBackend#getUriForFile(Context, File)} so both
 * LibreSpan's canonical-path guard and AndroidX FileProvider's XML path mapping participate.
 */
@RunWith(AndroidJUnit4.class)
public class FileProviderSecurityInstrumentationTest {

    private Context context;
    private final List<File> cleanup = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    @After
    public void tearDown() {
        for (int i = cleanup.size() - 1; i >= 0; --i) {
            deleteRecursively(cleanup.get(i));
        }
        cleanup.clear();
    }

    @Test
    public void everyDeclaredAppOwnedRootCanBeExposedAndRead() throws Exception {
        final RootSpec[] roots = {
            new RootSpec(context.getFilesDir(), "Images"),
            new RootSpec(context.getFilesDir(), "Videos"),
            new RootSpec(context.getFilesDir(), "Files"),
            new RootSpec(context.getFilesDir(), "Recordings"),
            new RootSpec(context.getFilesDir(), "Media"),
            new RootSpec(context.getCacheDir(), "Camera"),
            new RootSpec(context.getCacheDir(), "logs"),
            new RootSpec(context.getCacheDir(), "VoiceRecordings"),
            new RootSpec(context.getCacheDir(), "SecureOutgoingImages"),
            new RootSpec(context.getCacheDir(), "SecureOutgoingVideos"),
            new RootSpec(context.getCacheDir(), "SecureContentExports"),
            new RootSpec(context.getCacheDir(), "SecureMediaReadCache"),
        };

        for (final RootSpec root : roots) {
            final File testDir =
                    new File(root.base, root.relative + "/provider-test-" + UUID.randomUUID());
            final File file = new File(testDir, "allowed.txt");
            writeByte(file, 0x41);
            cleanup.add(testDir);

            final Uri uri = FileBackend.getUriForFile(context, file);

            assertEquals("content", uri.getScheme());
            assertEquals(FileBackend.getAuthority(context), uri.getAuthority());
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                assertNotNull(input);
                assertEquals(0x41, input.read());
            }
        }
    }

    @Test
    public void dotDotTraversalOutsideDeclaredRootIsRejected() throws Exception {
        final File outsideDir =
                new File(context.getFilesDir(), "ProviderOutside-" + UUID.randomUUID());
        final File outside = new File(outsideDir, "secret.txt");
        writeByte(outside, 0x42);
        cleanup.add(outsideDir);

        final File images = new File(context.getFilesDir(), "Images");
        if (!images.exists() && !images.mkdirs()) {
            throw new IllegalStateException("unable to create Images root");
        }
        final File traversal =
                new File(images, "../" + outsideDir.getName() + "/" + outside.getName());

        expectSecurityException(traversal);
    }

    @Test
    public void symlinkFromAllowedRootToOutsideFileIsRejected() throws Exception {
        final File outsideDir =
                new File(context.getCacheDir(), "ProviderOutside-" + UUID.randomUUID());
        final File outside = new File(outsideDir, "secret.txt");
        writeByte(outside, 0x43);
        cleanup.add(outsideDir);

        final File linkDir =
                new File(context.getFilesDir(), "Images/provider-link-" + UUID.randomUUID());
        if (!linkDir.mkdirs()) {
            throw new IllegalStateException("unable to create symlink test directory");
        }
        cleanup.add(linkDir);
        final File link = new File(linkDir, "escape.txt");
        Os.symlink(outside.getAbsolutePath(), link.getAbsolutePath());

        expectSecurityException(link);
    }

    private void expectSecurityException(final File file) {
        try {
            FileBackend.getUriForFile(context, file);
            fail("expected SecurityException for " + file);
        } catch (final SecurityException expected) {
            // expected
        }
    }

    private static void writeByte(final File file, final int value) throws Exception {
        final File parent = file.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new IllegalStateException("unable to create test directory");
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value);
        }
    }

    private static void deleteRecursively(final File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) {
                for (final File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static final class RootSpec {
        final File base;
        final String relative;

        RootSpec(final File base, final String relative) {
            this.base = base;
            this.relative = relative;
        }
    }
}
