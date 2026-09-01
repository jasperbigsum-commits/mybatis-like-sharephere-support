package io.github.jasper.mybatis.encrypt.core.support;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 轻量雪花算法 id 生成器。
 *
 * <p>用于独立表主键生成，避免外部表主键依赖数据库自增或返回 generated keys。
 * 这里保留与 MyBatis-Plus 类似的 64 位 long 结构：时间戳 + 数据中心 + 工作节点 + 序列号。</p>
 */
final class SnowflakeIdGenerator {

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

    SnowflakeIdGenerator() {
        this.workerId = resolveWorkerId();
        this.datacenterId = resolveDatacenterId();
    }

    /**
     * 生成下一个 long 型雪花 id。
     *
     * @return 全局近似有序的 long 主键
     */
    synchronized long nextId() {
        long timestamp = currentTimeMillis();
        if (timestamp < lastTimestamp) {
            timestamp = waitUntil(lastTimestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitUntil(long targetTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp < targetTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    private long resolveWorkerId() {
        String processName = ManagementFactory.getRuntimeMXBean().getName();
        return normalizeToRange(processName == null ? 0 : processName.hashCode(), MAX_WORKER_ID);
    }

    private long resolveDatacenterId() {
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            return normalizeToRange(hostName == null ? 0 : hostName.hashCode(), MAX_DATACENTER_ID);
        } catch (UnknownHostException ignore) {
            return 0L;
        }
    }

    private long normalizeToRange(int source, long maxValue) {
        return (source & Integer.MAX_VALUE) % (maxValue + 1);
    }
}
