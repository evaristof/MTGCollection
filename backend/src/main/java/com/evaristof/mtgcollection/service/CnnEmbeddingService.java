package com.evaristof.mtgcollection.service;

import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.translate.TranslateException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

@Service
public class CnnEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(CnnEmbeddingService.class);
    private static final String MODEL_RESOURCE = "/models/efficientnet_b0_features.onnx";
    private static final int EMBEDDING_DIM = 1280;
    private static final int INPUT_SIZE = 224;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private ZooModel<BufferedImage, float[]> model;

    @PostConstruct
    void init() {
        try {
            Path modelDir = Files.createTempDirectory("djl-model");
            Path modelFile = modelDir.resolve("efficientnet_b0_features.onnx");
            try (InputStream is = getClass().getResourceAsStream(MODEL_RESOURCE)) {
                if (is == null) {
                    log.warn("CNN model not found in resources: {}", MODEL_RESOURCE);
                    return;
                }
                Files.copy(is, modelFile, StandardCopyOption.REPLACE_EXISTING);
            }

            Criteria<BufferedImage, float[]> criteria = Criteria.builder()
                    .setTypes(BufferedImage.class, float[].class)
                    .optModelPath(modelDir)
                    .optModelName("efficientnet_b0_features")
                    .optTranslator(new EfficientNetTranslator())
                    .optEngine("OnnxRuntime")
                    .build();

            model = criteria.loadModel();
            log.info("CNN embedding model loaded (EfficientNet-B0, {}D)", EMBEDDING_DIM);
        } catch (ModelNotFoundException | MalformedModelException | IOException e) {
            log.warn("Failed to load CNN model, CNN matching disabled: {}", e.getMessage());
        }
    }

    @PreDestroy
    void destroy() {
        if (model != null) {
            model.close();
        }
    }

    public boolean isAvailable() {
        return model != null;
    }

    public float[] extractEmbedding(BufferedImage image) throws TranslateException {
        if (model == null) {
            throw new IllegalStateException("CNN model not loaded");
        }
        try (Predictor<BufferedImage, float[]> predictor = model.newPredictor()) {
            return predictor.predict(image);
        }
    }

    public float[] extractArtEmbedding(BufferedImage image) throws TranslateException {
        BufferedImage art = extractArtRegion(image);
        return extractEmbedding(art);
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom > 0 ? dot / denom : 0.0;
    }

    public static String embeddingToBase64(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float v : embedding) {
            buffer.putFloat(v);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    public static float[] base64ToEmbedding(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.getFloat();
        }
        return result;
    }

    private BufferedImage extractArtRegion(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int artX = (int) (w * 0.05);
        int artY = (int) (h * 0.08);
        int artW = (int) (w * 0.90);
        int artH = (int) (h * 0.47);
        return image.getSubimage(artX, artY, artW, artH);
    }

    private static class EfficientNetTranslator implements Translator<BufferedImage, float[]> {

        @Override
        public NDList processInput(TranslatorContext ctx, BufferedImage input) {
            NDManager manager = ctx.getNDManager();
            java.awt.Image scaled = input.getScaledInstance(INPUT_SIZE, INPUT_SIZE, java.awt.Image.SCALE_SMOOTH);
            BufferedImage resized = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = resized.createGraphics();
            g.drawImage(scaled, 0, 0, null);
            g.dispose();

            float[] data = new float[3 * INPUT_SIZE * INPUT_SIZE];
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                    int rgb = resized.getRGB(x, y);
                    float r = ((rgb >> 16) & 0xFF) / 255.0f;
                    float g2 = ((rgb >> 8) & 0xFF) / 255.0f;
                    float b = (rgb & 0xFF) / 255.0f;
                    int offset = y * INPUT_SIZE + x;
                    data[offset] = (r - MEAN[0]) / STD[0];
                    data[INPUT_SIZE * INPUT_SIZE + offset] = (g2 - MEAN[1]) / STD[1];
                    data[2 * INPUT_SIZE * INPUT_SIZE + offset] = (b - MEAN[2]) / STD[2];
                }
            }

            NDArray array = manager.create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
            return new NDList(array);
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            return list.singletonOrThrow().toFloatArray();
        }

        @Override
        public Batchifier getBatchifier() {
            return null;
        }
    }
}
