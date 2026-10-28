package app.aurelia.tv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import app.aurelia.tv.jellyfin.Api;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Artwork kept on disk as well as in memory, so after a restart Home's cards show their pictures at once rather
 * than fetching each again. Only versioned artwork (a {@code tag=} in its address, which changes when the picture
 * does) is kept, so nothing kept is ever out of date. The oldest goes once there is more than {@link #MAX_BYTES}.
 */
public final class ImageDisk {

    private static final long MAX_BYTES = 48L * 1024 * 1024;
    /** Writes between tidy-ups. */
    private static final int PRUNE_EVERY = 25;

    private static volatile File dir;
    private static int writes;

    private ImageDisk() { }

    /** Where to keep it: once, from Home. Until then artwork comes from the server only. */
    public static void init(Context context) {
        File d = new File(context.getCacheDir(), "images");
        if (d.isDirectory() || d.mkdirs()) dir = d;
    }

    /** {@code url}'s picture: from disk when it is kept, else from the server, then kept. Blocks. */
    public static Bitmap get(String url, String auth) throws IOException {
        File file = file(url);
        if (file != null && file.isFile()) {
            Bitmap kept = BitmapFactory.decodeFile(file.getPath());
            if (kept != null) {
                touch(file);
                return kept;
            }
        }
        byte[] bytes = Api.getBytes(url, auth);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap != null && file != null) keep(file, bytes);
        return bitmap;
    }

    /**
     * {@code url}'s picture onto the disk ahead of when it is needed, without decoding it: only its header is read,
     * to be sure it is a picture. Does nothing for one that cannot be kept (untagged) or is kept already. Blocks.
     */
    public static void prefetch(String url, String auth) throws IOException {
        File file = file(url);
        if (file == null) return;
        if (file.isFile()) {
            touch(file);
            return;
        }
        byte[] bytes = Api.getBytes(url, auth);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth > 0) keep(file, bytes);
    }

    /** Recently used: kept longest. */
    private static void touch(File file) {
        //noinspection ResultOfMethodCallIgnored
        file.setLastModified(System.currentTimeMillis());
    }

    private static File file(String url) {
        File d = dir;
        if (d == null || url == null || !url.contains("tag=")) return null;
        return new File(d, hash(url));
    }

    /** The bytes the server sent, as they are: encoding the decoded picture again would cost far more. */
    private static void keep(File file, byte[] bytes) {
        File part = new File(file.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(bytes);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return;
        }
        if (!part.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        synchronized (ImageDisk.class) {
            if (++writes % PRUNE_EVERY == 0) prune(file.getParentFile());
        }
    }

    private static void prune(File d) {
        File[] files = d.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_BYTES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= MAX_BYTES * 3 / 4) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String hash(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : digest) s.append(String.format("%02x", b));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(url.hashCode());
        }
    }
}
