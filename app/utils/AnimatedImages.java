package utils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Animated images from still frames, with the JDK alone. The JDK has no WebP reader or writer, so the
 * browser encodes each frame as WebP and {@link #webp} only joins them: an animated WebP is a RIFF container
 * holding ordinary stills, a header, and a duration per frame. {@link #gif} decodes PNG frames and writes a
 * GIF through ImageIO's GIF writer, which reduces each frame to 256 colours.
 */
public final class AnimatedImages {

    public static final int MAX_FRAMES = 1000;
    private static final int MAX_FRAME_MS = 0xFFFFFF;
    private static final String GIF_FORMAT = "javax_imageio_gif_image_1.0";

    /** Pixel dimensions of an image. */
    public record Size(int width, int height) {
        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    /** A still WebP's image chunks ({@code ALPH} and {@code VP8 } or {@code VP8L}) ready to go inside a frame. */
    @SuppressWarnings("java:S6218") // never compared or printed: read once by the muxer
    private record Still(Size size, byte[] chunks, boolean alpha) {}

    private AnimatedImages() {}

    /** The width and height of a PNG (or any image ImageIO reads), without decoding its pixels. */
    public static Size imageSize(byte[] file) throws IOException {
        try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IllegalArgumentException("not an image");
            var reader = readers.next();
            try {
                reader.setInput(in);
                return new Size(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    /** The width and height of a still WebP file. */
    public static Size webpSize(byte[] file) {
        return parse(file).size();
    }

    /**
     * An animated WebP that plays {@code frames} in order, {@code frameMs} each, forever. Every frame must be a still
     * WebP of one size.
     *
     * @throws IllegalArgumentException for no frames, too many, a bad time, a frame that is not a still WebP, or
     *         frames of different sizes
     */
    public static byte[] webp(List<byte[]> frames, int frameMs) {
        checkInputs(frames.size(), frameMs);
        var stills = frames.stream().map(AnimatedImages::parse).toList();
        var canvas = stills.getFirst().size();
        var alpha = false;
        for (var still : stills) {
            if (!still.size().equals(canvas)) {
                throw new IllegalArgumentException("frames differ in size: " + canvas + " and " + still.size());
            }
            alpha |= still.alpha();
        }
        var body = new ByteArrayOutputStream();
        var vp8x = new ByteArrayOutputStream();
        vp8x.write(0x02 | (alpha ? 0x10 : 0));
        write24(vp8x, 0);
        write24(vp8x, canvas.width() - 1);
        write24(vp8x, canvas.height() - 1);
        chunk(body, "VP8X", vp8x.toByteArray());
        chunk(body, "ANIM", new byte[6]);
        for (var still : stills) {
            var frame = new ByteArrayOutputStream();
            write24(frame, 0);
            write24(frame, 0);
            write24(frame, canvas.width() - 1);
            write24(frame, canvas.height() - 1);
            write24(frame, frameMs);
            frame.write(0x02);
            frame.writeBytes(still.chunks());
            chunk(body, "ANMF", frame.toByteArray());
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.ISO_8859_1));
        write32(out, 4 + body.size());
        out.writeBytes("WEBP".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /**
     * An animated GIF that plays the PNG {@code frames} in order, {@code frameMs} each (to a hundredth of a second),
     * forever.
     *
     * @throws IllegalArgumentException for no frames, too many, a bad time, a frame that is not an image, or frames of
     *         different sizes
     * @throws IOException when the GIF cannot be written
     */
    public static byte[] gif(List<byte[]> frames, int frameMs) throws IOException {
        checkInputs(frames.size(), frameMs);
        var delay = Integer.toString(Math.max(2, frameMs / 10));
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var out = new ByteArrayOutputStream(); var ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            Size canvas = null;
            var index = 0;
            for (var png : frames) {
                var image = ImageIO.read(new ByteArrayInputStream(png));
                if (image == null) throw new IllegalArgumentException("not an image");
                var size = new Size(image.getWidth(), image.getHeight());
                if (canvas == null) {
                    canvas = size;
                } else if (!canvas.equals(size)) {
                    throw new IllegalArgumentException("frames differ in size: " + canvas + " and " + size);
                }
                var rgb = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_RGB);
                var g = rgb.createGraphics();
                g.drawImage(image, 0, 0, Color.WHITE, null);
                g.dispose();
                var param = writer.getDefaultWriteParam();
                var meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(rgb), param);
                var root = (IIOMetadataNode) meta.getAsTree(GIF_FORMAT);
                var control = child(root, "GraphicControlExtension");
                control.setAttribute("disposalMethod", "none");
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("transparentColorIndex", "0");
                control.setAttribute("delayTime", delay);
                if (index++ == 0) {
                    var loop = new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID", "NETSCAPE");
                    loop.setAttribute("authenticationCode", "2.0");
                    loop.setUserObject(new byte[] {1, 0, 0});
                    child(root, "ApplicationExtensions").appendChild(loop);
                }
                meta.setFromTree(GIF_FORMAT, root);
                writer.writeToSequence(new IIOImage(rgb, null, meta), param);
            }
            writer.endWriteSequence();
            ios.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static void checkInputs(int count, int frameMs) {
        if (count == 0) throw new IllegalArgumentException("no frames");
        if (count > MAX_FRAMES) throw new IllegalArgumentException("at most " + MAX_FRAMES + " frames");
        if (frameMs < 1 || frameMs > MAX_FRAME_MS) {
            throw new IllegalArgumentException("frame time must be between 1 and " + MAX_FRAME_MS + " ms");
        }
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeName().equals(name)) return (IIOMetadataNode) node;
        }
        var created = new IIOMetadataNode(name);
        root.appendChild(created);
        return created;
    }

    private static Still parse(byte[] f) {
        if (f.length < 20 || !is(f, 0, "RIFF") || !is(f, 8, "WEBP")) throw new IllegalArgumentException("not a WebP image");
        var chunks = new ByteArrayOutputStream();
        Size size = null;
        var alpha = false;
        var pos = 12;
        while (pos + 8 <= f.length) {
            var id = new String(f, pos, 4, StandardCharsets.ISO_8859_1);
            var length = read32(f, pos + 4);
            var data = pos + 8;
            if (length > f.length - data) throw new IllegalArgumentException("truncated WebP chunk");
            var len = (int) length;
            switch (id) {
                case "ANIM", "ANMF" -> throw new IllegalArgumentException("the WebP is already animated");
                case "ALPH" -> {
                    alpha = true;
                    keep(chunks, f, pos, len);
                }
                case "VP8 " -> {
                    if (len < 10 || (f[data + 3] & 0xFF) != 0x9D || f[data + 4] != 0x01 || f[data + 5] != 0x2A) {
                        throw new IllegalArgumentException("not a WebP image");
                    }
                    size = new Size(read16(f, data + 6) & 0x3FFF, read16(f, data + 8) & 0x3FFF);
                    keep(chunks, f, pos, len);
                }
                case "VP8L" -> {
                    if (len < 5 || f[data] != 0x2F) throw new IllegalArgumentException("not a WebP image");
                    var bits = read32(f, data + 1);
                    size = new Size(1 + (int) (bits & 0x3FFF), 1 + (int) ((bits >>> 14) & 0x3FFF));
                    alpha |= ((bits >>> 28) & 1) == 1;
                    keep(chunks, f, pos, len);
                }
                default -> { /* VP8X, ICCP, EXIF and XMP describe the still, not the frame */ }
            }
            pos = data + len + (len & 1);
        }
        if (size == null) throw new IllegalArgumentException("no image data in the WebP");
        return new Still(size, chunks.toByteArray(), alpha);
    }

    private static void keep(ByteArrayOutputStream out, byte[] f, int pos, int len) {
        out.write(f, pos, 8 + len);
        if ((len & 1) == 1) out.write(0);
    }

    private static void chunk(ByteArrayOutputStream out, String id, byte[] data) {
        out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        write32(out, data.length);
        out.writeBytes(data);
        if ((data.length & 1) == 1) out.write(0);
    }

    private static boolean is(byte[] f, int at, String id) {
        for (var i = 0; i < 4; i++) if (f[at + i] != id.charAt(i)) return false;
        return true;
    }

    private static int read16(byte[] f, int at) {
        return (f[at] & 0xFF) | (f[at + 1] & 0xFF) << 8;
    }

    private static long read32(byte[] f, int at) {
        return (f[at] & 0xFFL) | (f[at + 1] & 0xFFL) << 8 | (f[at + 2] & 0xFFL) << 16 | (f[at + 3] & 0xFFL) << 24;
    }

    private static void write24(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
    }

    private static void write32(ByteArrayOutputStream out, int v) {
        write24(out, v);
        out.write((v >>> 24) & 0xFF);
    }
}
