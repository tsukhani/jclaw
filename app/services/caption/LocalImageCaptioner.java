package services.caption;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.NoopTranslator;
import models.MessageAttachment;
import services.AttachmentService;
import services.EventLogger;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-213: offline, in-JVM image captioner — the vision analogue of whisper-jni. Runs ViT-GPT2
 * (Xenova/vit-gpt2-image-captioning) via DJL + ONNX Runtime entirely in-process:
 * image → ViT {@code pixel_values} → encoder.onnx → {@code last_hidden_state} → greedy
 * autoregressive decode over the GPT-2 decoder.onnx → caption. The text-only-model fallback so a
 * non-vision agent still "sees" an uploaded image as a description.
 *
 * <p>DJL's ONNX-Runtime engine is inference-only (no resize/normalize/argMax NDArray ops), so image
 * preprocessing and argmax run in plain Java and ONNX Runtime is used only for forward passes —
 * hence no PyTorch engine and a small native footprint. The two ONNX sessions + the GPT-2 tokenizer
 * load once per JVM and are reused under {@link #LOCK}: DJL predictors aren't thread-safe and one
 * image at a time matches the fallback workload, mirroring {@code WhisperJniTranscriber}.
 */
public final class LocalImageCaptioner implements ImageCaptionService {

    private static final String CATEGORY = "caption";
    private static final String CHANNEL = "image";
    private static final long DECODER_START = 50256; // GPT-2 bos == eos (config.json)
    private static final long EOS = 50256;
    private static final int MAX_NEW_TOKENS = 20;
    private static final int SIZE = 224;

    /** Directory holding {@code encoder.onnx} + {@code decoder.onnx} + {@code tokenizer.json}.
     *  Defaults to {@link VlmModelManager}'s per-model cache for the default model; overridable via
     *  {@link #setModelDir} (tests, or to select a different downloaded model). */
    private static Path modelDir = VlmModelManager.localDir(VlmModel.DEFAULT);

    private static final Object LOCK = new Object();
    private static volatile boolean loaded;
    private static ZooModel<NDList, NDList> encModel;
    private static ZooModel<NDList, NDList> decModel;
    private static Predictor<NDList, NDList> encoder;
    private static Predictor<NDList, NDList> decoder;
    private static HuggingFaceTokenizer tokenizer;

    /** Point the captioner at the directory containing the ONNX graphs + tokenizer. */
    public static void setModelDir(Path dir) {
        synchronized (LOCK) {
            modelDir = dir;
            loaded = false; // force reload against the new dir on next use
        }
    }

    /** True iff the three model files are present on disk (cheap; no native load). */
    public static boolean modelFilesPresent() {
        return java.nio.file.Files.isRegularFile(modelDir.resolve("encoder.onnx"))
                && java.nio.file.Files.isRegularFile(modelDir.resolve("decoder.onnx"))
                && java.nio.file.Files.isRegularFile(modelDir.resolve("tokenizer.json"));
    }

    @Override
    public String caption(MessageAttachment attachment) {
        try {
            return captionImageBytes(AttachmentService.readBytes(attachment));
        } catch (Exception e) {
            EventLogger.warn(CATEGORY, null, CHANNEL, "Local image captioning failed: " + e.getMessage());
            return "";
        }
    }

    /** Pure pipeline over raw image bytes — the unit/integration-test seam (no JPA needed). */
    public String captionImageBytes(byte[] imageBytes) throws Exception {
        ensureLoaded();
        synchronized (LOCK) {
            // Per-call scope so the NDArrays we allocate are freed after each caption.
            try (NDManager scope = encModel.getNDManager().newSubManager()) {
                NDArray pixels = scope.create(preprocess(imageBytes), new Shape(1, 3, SIZE, SIZE));
                pixels.setName("pixel_values");

                NDList encOut = encoder.predict(new NDList(pixels));
                NDArray encHidden = encOut.get(0);
                NDArray hidden = scope.create(encHidden.toFloatArray(), new Shape(encHidden.getShape().getShape()));
                hidden.setName("encoder_hidden_states");

                long[] ids = {DECODER_START};
                List<Long> generated = new ArrayList<>();
                for (int step = 0; step < MAX_NEW_TOKENS; step++) {
                    NDArray inputIds = scope.create(ids).reshape(new Shape(1, ids.length));
                    inputIds.setName("input_ids");
                    NDList decOut = decoder.predict(new NDList(inputIds, hidden));
                    NDArray logits = pickLogits(decOut);
                    long[] ls = logits.getShape().getShape();
                    int t = (int) ls[1], vocab = (int) ls[2];
                    float[] all = logits.toFloatArray();
                    int off = (t - 1) * vocab, best = 0;
                    float bv = Float.NEGATIVE_INFINITY;
                    for (int v = 0; v < vocab; v++) {
                        if (all[off + v] > bv) { bv = all[off + v]; best = v; }
                    }
                    if (best == EOS) break;
                    generated.add((long) best);
                    long[] grown = new long[ids.length + 1];
                    System.arraycopy(ids, 0, grown, 0, ids.length);
                    grown[ids.length] = best;
                    ids = grown;
                }
                long[] g = generated.stream().mapToLong(Long::longValue).toArray();
                return tokenizer.decode(g, true).trim();
            }
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            try {
                encModel = onnx("encoder").loadModel();
                decModel = onnx("decoder").loadModel();
                encoder = encModel.newPredictor();
                decoder = decModel.newPredictor();
                tokenizer = HuggingFaceTokenizer.newInstance(modelDir.resolve("tokenizer.json"));
                loaded = true;
                EventLogger.info(CATEGORY, null, CHANNEL,
                        "Local image captioner loaded (ViT-GPT2 ONNX) from " + modelDir);
            } catch (RuntimeException e) {
                EventLogger.error(CATEGORY, null, CHANNEL,
                        "Failed to load local image captioner from " + modelDir + ": " + e.getMessage());
                throw e;
            } catch (Exception e) {
                EventLogger.error(CATEGORY, null, CHANNEL,
                        "Failed to load local image captioner from " + modelDir + ": " + e.getMessage());
                throw new IllegalStateException("local image captioner unavailable", e);
            }
        }
    }

    private static Criteria<NDList, NDList> onnx(String name) {
        return Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(modelDir).optModelName(name)
                .optEngine("OnnxRuntime").optTranslator(new NoopTranslator()).build();
    }

    /** The no-past decoder emits {@code logits} plus {@code present.*} KV; pick the [.,.,vocab] one. */
    private static NDArray pickLogits(NDList out) {
        for (NDArray a : out) {
            if ("logits".equals(a.getName())) return a;
        }
        for (NDArray a : out) {
            Shape s = a.getShape();
            if (s.dimension() == 3 && s.get(2) > 40000) return a;
        }
        return out.get(0);
    }

    /** Bilinear resize to 224² + CHW + normalize (x/255−0.5)/0.5, all in plain Java. */
    private static float[] preprocess(byte[] imageBytes) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (src == null) throw new IllegalArgumentException("unreadable image bytes");
        BufferedImage dst = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, SIZE, SIZE, null);
        g.dispose();
        float[] out = new float[3 * SIZE * SIZE];
        int hw = SIZE * SIZE;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int rgb = dst.getRGB(x, y);
                int idx = y * SIZE + x;
                out[idx]          = ((rgb >> 16 & 0xff) / 255f - 0.5f) / 0.5f;
                out[hw + idx]     = ((rgb >> 8 & 0xff) / 255f - 0.5f) / 0.5f;
                out[2 * hw + idx] = ((rgb & 0xff) / 255f - 0.5f) / 0.5f;
            }
        }
        return out;
    }
}
