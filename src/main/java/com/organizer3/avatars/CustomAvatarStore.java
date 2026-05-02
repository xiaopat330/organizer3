package com.organizer3.avatars;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stores user-curated actress avatar images under {@code <dataDir>/actress-custom-avatars/{id}.jpg}.
 *
 * <p>Input images (JPEG or PNG) are re-encoded to JPEG quality 0.88. Images larger than 600×600
 * are downscaled; images smaller than 150×150 are rejected. Input must be square (width == height).
 *
 * <p>Writes are atomic: bytes go to a {@code .tmp} file first, then moved to the final path.
 *
 * <p>Pure I/O — no database interaction.
 *
 * <p>See spec/PROPOSAL_CUSTOM_PROFILE_IMAGES.md.
 */
@Slf4j
public class CustomAvatarStore {

    public static final int MIN_SIZE = 150;
    public static final int MAX_SIZE = 600;
    private static final String SUBDIR = "actress-custom-avatars";
    private static final float JPEG_QUALITY = 0.88f;

    private final Path dataDir;

    public CustomAvatarStore(Path dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * Validates, normalizes, and saves the image for {@code actressId}.
     *
     * @param actressId  actress primary key (used as filename)
     * @param imageBytes raw JPEG or PNG bytes from the client
     * @return path relative to dataDir (e.g. {@code actress-custom-avatars/42.jpg})
     * @throws IllegalArgumentException if the image is invalid (not square, too small, unreadable)
     * @throws IOException              if the file cannot be written
     */
    public String save(long actressId, byte[] imageBytes) throws IOException {
        BufferedImage src = decode(imageBytes);
        int w = src.getWidth();
        int h = src.getHeight();
        if (w != h) {
            throw new IllegalArgumentException("Image must be square, got %dx%d".formatted(w, h));
        }
        if (w < MIN_SIZE) {
            throw new IllegalArgumentException(
                    "Image must be at least %dx%d, got %dx%d".formatted(MIN_SIZE, MIN_SIZE, w, h));
        }

        BufferedImage out = (w > MAX_SIZE) ? downscale(src, MAX_SIZE) : src;
        byte[] jpeg = encodeJpeg(out, JPEG_QUALITY);

        Path dir = dataDir.resolve(SUBDIR);
        Files.createDirectories(dir);
        String filename = actressId + ".jpg";
        Path target = dir.resolve(filename);
        Path tmp = dir.resolve(filename + ".tmp");
        Files.write(tmp, jpeg);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        String rel = SUBDIR + "/" + filename;
        log.debug("Saved custom avatar for actress {}: {}", actressId, rel);
        return rel;
    }

    /**
     * Deletes the custom avatar file for {@code actressId}, if it exists.
     *
     * @return true if the file existed and was deleted; false if there was nothing to delete
     */
    public boolean delete(long actressId) {
        Path target = dataDir.resolve(SUBDIR).resolve(actressId + ".jpg");
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) log.debug("Deleted custom avatar for actress {}", actressId);
            return deleted;
        } catch (IOException e) {
            log.warn("Could not delete custom avatar for actress {}: {}", actressId, e.getMessage());
            return false;
        }
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Image bytes must not be empty");
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) {
            throw new IllegalArgumentException("Could not decode image — unsupported format");
        }
        return img;
    }

    private static BufferedImage downscale(BufferedImage src, int targetSize) {
        BufferedImage scaled = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB);
        var g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, targetSize, targetSize, null);
        g.dispose();
        return scaled;
    }

    static byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
        // Convert to RGB first — JPEG encoder rejects ARGB images.
        BufferedImage rgb;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = rgb.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
        } else {
            rgb = img;
        }

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(quality);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(buf)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), params);
        } finally {
            writer.dispose();
        }
        return buf.toByteArray();
    }
}
