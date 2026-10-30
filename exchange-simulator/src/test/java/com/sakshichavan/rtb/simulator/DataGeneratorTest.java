package com.sakshichavan.rtb.simulator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataGeneratorTest {

    private static String generate(long rows, long seed) throws IOException {
        StringWriter out = new StringWriter();
        DataGenerator.generate(out, rows, seed);
        return out.toString();
    }

    @Test
    void writesAHeaderAndOneRowPerImpression() throws IOException {
        String[] lines = generate(1_000, 1).split("\n");

        assertEquals(DataGenerator.HEADER, lines[0]);
        assertEquals(1_001, lines.length);
        assertEquals(8, lines[1].split(",", -1).length, "every row has all 8 columns");
    }

    @Test
    void theSameSeedProducesIdenticalData() throws IOException {
        assertEquals(generate(5_000, 7), generate(5_000, 7));
    }

    @Test
    void clickRateTracksTheTrueProbabilities() throws IOException {
        DataGenerator.Summary summary = DataGenerator.generate(new StringWriter(), 200_000, 3);

        assertEquals(summary.meanTrueCtr(), summary.clickRate(), 0.001);
    }

    @Test
    void theOracleBeatsTheConstantBaseline() throws IOException {
        DataGenerator.Summary summary = DataGenerator.generate(new StringWriter(), 200_000, 3);

        assertTrue(summary.oracleLogLoss() < summary.baselineLogLoss(),
                "oracle " + summary.oracleLogLoss() + " vs baseline " + summary.baselineLogLoss());
    }
}
