package com.sakshichavan.rtb.simulator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void parsesFrequencyCapSpec() {
        assertEquals(Map.of("c-a", 3, "c-b", 5), SimulatorMain.parseCaps("c-a=3, c-b=5"));
        assertTrue(SimulatorMain.parseCaps(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SimulatorMain.parseCaps("c-a:3"));
    }
}
