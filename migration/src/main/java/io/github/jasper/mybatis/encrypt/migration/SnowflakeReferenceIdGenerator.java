package io.github.jasper.mybatis.encrypt.migration;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Lightweight long reference id generator for separate-table rows.
 */
public class SnowflakeReferenceIdGenerator implements ReferenceIdGenerator {

    private static final long EPOCH = 1704067200000L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final long workerId;
    private final long datacenterId;

    private long lastTimestamp = -1L;
    private long sequence;

    /**
     * Create a generator with runtime-derived worker and datacenter ids.
     */
    public SnowflakeReferenceIdGenerator() {
        this.workerId = normalizeToRange(resolveRuntimeName().hashCode(), MAX_WORKER_ID);
        this.datacenterId = normalizeToRange(resolveHostName().hashCode(), MAX_DATACENTER_ID);
    }

    @Override
    public synchronized Object nextReferenceId(EntityMigrationColumnPlan plan, MigrationRecord record) {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            timestamp = waitUntil(lastTimestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                timestamp = waitUntil(lastTimestamp + 1L);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return Long.valueOf(((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence);
    }

    private long waitUntil(long targetTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp < targetTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    private String resolveRuntimeName() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        return runtimeName == null ? "migration" : runtimeName;
    }

    private String resolveHostName() {
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            return hostName == null ? "migration" : hostName;
        } catch (UnknownHostException ignore) {
            return "migration";
        }
    }

    private long normalizeToRange(int source, long maxValue) {
        return (source & Integer.MAX_VALUE) % (maxValue + 1L);
    }
}
