package io.jenkins.plugins.queuemonitor;

import org.junit.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.Assert.*;

public class QueueSnapshotTest {

    @Test
    public void saturatedWhenAllExecutorsBusy() {
        QueueSnapshot snap = new QueueSnapshot(
            Instant.now(), 5,
            Map.of("linux", 5),
            10, 10,
            Map.of("linux", 10),
            Map.of("linux", 10)
        );
        assertTrue(snap.isLabelSaturated("linux"));
    }

    @Test
    public void notSaturatedWhenExecutorsFree() {
        QueueSnapshot snap = new QueueSnapshot(
            Instant.now(), 2,
            Map.of("linux", 2),
            10, 6,
            Map.of("linux", 6),
            Map.of("linux", 10)
        );
        assertFalse(snap.isLabelSaturated("linux"));
    }

    @Test
    public void utilizationPercent() {
        QueueSnapshot snap = new QueueSnapshot(
            Instant.now(), 0,
            Map.of(),
            10, 5,
            Map.of(),
            Map.of()
        );
        assertEquals(50.0, snap.executorUtilizationPercent(), 0.01);
    }
}
