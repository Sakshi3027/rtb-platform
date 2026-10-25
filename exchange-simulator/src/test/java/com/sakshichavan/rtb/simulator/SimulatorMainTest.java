package com.sakshichavan.rtb.simulator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimulatorMainTest {

    @Test
    void parsesFlagsAfterCommand() {
        Map<String, String> flags = SimulatorMain.parseFlags(
                new String[] {"run", "--qps", "500", "--target", "http://localhost:9000/bid"});

        assertEquals("500", flags.get("qps"));
        assertEquals("http://localhost:9000/bid", flags.get("target"));
    }

    @Test
    void rejectsFlagWithoutValue() {
        assertThrows(IllegalArgumentException.class,
                () -> SimulatorMain.parseFlags(new String[] {"run", "--qps"}));
    }
}
