package com.dede.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeMetricsTest {

    private static CodeNode node() {
        return new CodeNode("class:a.B", "B", NodeType.CLASS, "a.B", null);
    }

    @Test
    void trafficPrefersRequestsThenRendersThenCalls() {
        CodeNode n = node();
        assertThat(RuntimeMetrics.traffic(n)).isNull();
        RuntimeMetrics.set(n, RuntimeMetrics.CALLS, 900L);
        assertThat(RuntimeMetrics.traffic(n)).isEqualTo(900L);
        RuntimeMetrics.set(n, RuntimeMetrics.RENDERS, 40L);
        assertThat(RuntimeMetrics.traffic(n)).isEqualTo(40L);
        RuntimeMetrics.set(n, RuntimeMetrics.REQUESTS, 0L);
        assertThat(RuntimeMetrics.traffic(n)).as("recorded zero is data, not absence").isZero();
    }

    @Test
    void valuesRoundTripThroughNodeProperties() {
        CodeNode n = node();
        RuntimeMetrics.set(n, RuntimeMetrics.AVG_MS, 12.345);
        assertThat(n.getProperties()).containsEntry("runtime.avgMs", "12.35");
        assertThat(RuntimeMetrics.get(n, RuntimeMetrics.AVG_MS)).isEqualTo(12.35);
        n.getProperties().put(RuntimeMetrics.CALLS, "n/a");
        assertThat(RuntimeMetrics.get(n, RuntimeMetrics.CALLS)).isNull();
        assertThat(RuntimeMetrics.COUNTS).allMatch(m -> m.startsWith(RuntimeMetrics.PREFIX));
    }
}
