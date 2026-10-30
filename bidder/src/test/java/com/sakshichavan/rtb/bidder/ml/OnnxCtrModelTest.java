package com.sakshichavan.rtb.bidder.ml;

import com.sakshichavan.rtb.common.features.ImpressionFeatures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxCtrModelTest {

    private static final double PARITY_TOLERANCE = 1e-5;

    private static OnnxCtrModel model;

    record GoldenRow(ImpressionFeatures features, double expectedCtr) {
    }

    @BeforeAll
    static void loadModel() {
        model = OnnxCtrModel.fromClasspath("/models/ctr-v1.onnx");
    }

    @AfterAll
    static void closeModel() {
        model.close();
    }

    private static List<GoldenRow> golden() throws IOException {
        InputStream in = OnnxCtrModelTest.class.getResourceAsStream("/golden/ctr_golden.csv");
        assertNotNull(in, "golden file missing from the test classpath");
        List<GoldenRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            assertEquals("campaign_id,size,country,category,segments,known_user,expected_ctr", header);
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",", -1);
                List<String> segments = fields[4].isEmpty() ? List.of() : Arrays.asList(fields[4].split("\\|"));
                ImpressionFeatures features = new ImpressionFeatures(
                        fields[0], fields[1], fields[2], fields[3], segments, "1".equals(fields[5]));
                rows.add(new GoldenRow(features, Double.parseDouble(fields[6])));
            }
        }
        return rows;
    }

    @Test
    void reproducesEveryPythonPredictionInTheGoldenFile() throws IOException {
        List<GoldenRow> golden = golden();
        double[] predicted = model.predict(golden.stream().map(GoldenRow::features).toList());

        double worst = 0;
        int worstRow = -1;
        for (int i = 0; i < golden.size(); i++) {
            double diff = Math.abs(predicted[i] - golden.get(i).expectedCtr());
            if (diff > worst) {
                worst = diff;
                worstRow = i;
            }
        }
        assertEquals(1_000, golden.size());
        assertTrue(worst <= PARITY_TOLERANCE,
                "Java differs from Python by " + worst + " at golden row " + worstRow + ": " + golden.get(worstRow));
    }

    @Test
    void aRowPredictsTheSameAloneOrInABatch() throws IOException {
        List<ImpressionFeatures> features = golden().stream().limit(20).map(GoldenRow::features).toList();
        double[] batched = model.predict(features);

        for (int i = 0; i < features.size(); i++) {
            assertEquals(batched[i], model.predict(List.of(features.get(i)))[0], 1e-9, "row " + i);
        }
    }

    @Test
    void unseenValuesStillProduceAValidProbability() {
        ImpressionFeatures unseen = new ImpressionFeatures(
                "c-brand-new-campaign", "970x250", "ATA", "IAB99", List.of(), true);

        double p = model.predict(List.of(unseen))[0];

        assertTrue(p > 0 && p < 1, "probability was " + p);
    }
}
