package com.sakshichavan.rtb.bidder.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.sakshichavan.rtb.common.audience.AudienceSegments;
import com.sakshichavan.rtb.common.features.ImpressionFeatures;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Click prediction with an ONNX model that contains its own feature encoder, so it takes raw string features.
 * One session is loaded at startup and shared by all threads (ONNX Runtime sessions are safe to call concurrently).
 * Each call runs on the calling thread only: small batches are faster without intra-op threading, and extra threads
 * would compete with the event loops. Input tensors live in native memory and are always closed after a call.
 */
public final class OnnxCtrModel implements CtrModel, AutoCloseable {

    static final String OUTPUT = "probabilities";
    private static final List<String> STRING_INPUTS = List.of("campaign_id", "size", "country", "category");
    private static final String KNOWN_USER = "known_user";
    private static final List<String> SEGMENT_INPUTS = AudienceSegments.ALL.stream()
            .map(segment -> "seg_" + segment.replace('-', '_'))
            .toList();

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String version;

    public static OnnxCtrModel fromClasspath(String resource) {
        try (InputStream in = OnnxCtrModel.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("model not found on classpath: " + resource);
            }
            return new OnnxCtrModel(in.readAllBytes(), resource.substring(resource.lastIndexOf('/') + 1));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read model " + resource, e);
        }
    }

    public static OnnxCtrModel fromFile(Path path) {
        try {
            return new OnnxCtrModel(Files.readAllBytes(path), path.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read model " + path, e);
        }
    }

    OnnxCtrModel(byte[] model, String version) {
        this.environment = OrtEnvironment.getEnvironment();
        this.version = version;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.session = environment.createSession(model, options);
            verifySchema();
        } catch (OrtException e) {
            throw new IllegalStateException("cannot load CTR model " + version, e);
        }
    }

    /** Fails at startup, not in production traffic, if the model expects different features than the bidder sends. */
    private void verifySchema() throws OrtException {
        Set<String> expected = new HashSet<>(STRING_INPUTS);
        expected.add(KNOWN_USER);
        expected.addAll(SEGMENT_INPUTS);
        Set<String> actual = session.getInputNames();
        if (!actual.equals(expected)) {
            throw new IllegalStateException("model " + version + " expects inputs " + actual
                    + " but the bidder provides " + expected);
        }
        if (!session.getOutputNames().contains(OUTPUT)) {
            throw new IllegalStateException("model " + version + " has no '" + OUTPUT + "' output");
        }
    }

    @Override
    public double[] predict(List<ImpressionFeatures> rows) {
        int n = rows.size();
        if (n == 0) {
            return new double[0];
        }
        String[] campaign = new String[n];
        String[] size = new String[n];
        String[] country = new String[n];
        String[] category = new String[n];
        float[] knownUser = new float[n];
        float[][] segments = new float[SEGMENT_INPUTS.size()][n];

        for (int i = 0; i < n; i++) {
            ImpressionFeatures row = rows.get(i);
            campaign[i] = row.campaignId();
            size[i] = row.size();
            country[i] = row.country();
            category[i] = row.category();
            knownUser[i] = row.knownUser() ? 1f : 0f;
            for (int s = 0; s < SEGMENT_INPUTS.size(); s++) {
                segments[s][i] = row.segments().contains(AudienceSegments.ALL.get(s)) ? 1f : 0f;
            }
        }

        long[] shape = {n, 1};
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("campaign_id", OnnxTensor.createTensor(environment, campaign, shape));
            inputs.put("size", OnnxTensor.createTensor(environment, size, shape));
            inputs.put("country", OnnxTensor.createTensor(environment, country, shape));
            inputs.put("category", OnnxTensor.createTensor(environment, category, shape));
            inputs.put(KNOWN_USER, OnnxTensor.createTensor(environment, FloatBuffer.wrap(knownUser), shape));
            for (int s = 0; s < SEGMENT_INPUTS.size(); s++) {
                inputs.put(SEGMENT_INPUTS.get(s), OnnxTensor.createTensor(environment, FloatBuffer.wrap(segments[s]), shape));
            }
            try (OrtSession.Result result = session.run(inputs, Set.of(OUTPUT))) {
                FloatBuffer probabilities = ((OnnxTensor) result.get(0)).getFloatBuffer();
                double[] predictions = new double[n];
                for (int i = 0; i < n; i++) {
                    predictions[i] = probabilities.get(2 * i + 1);
                }
                return predictions;
            }
        } catch (OrtException e) {
            throw new IllegalStateException("CTR inference failed for model " + version, e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    public String version() {
        return version;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException("cannot close CTR model " + version, e);
        }
    }
}
