package com.cz.common.util;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 雪花 ID 生成器。
 *
 * <p>用于为每条短信生成全链路唯一标识（受理时产生，贯穿消息、检索库文档主键与回执关联键）。</p>
 *
 * <p><b>位布局（共 63 位，最高位恒为 0 以保证 ID 为正数）</b>：</p>
 * <pre>
 *  ┌─ 1 位符号位（恒 0）
 *  │   ┌─ 41 位时间戳（相对起始纪元的毫秒数，可用约 69 年）
 *  │   │            ┌─ 5 位机器标识（0~31）
 *  │   │            │      ┌─ 5 位服务标识（0~31）
 *  │   │            │      │      ┌─ 12 位序列号（0~4095，单机每毫秒上限）
 *  0 | timestamp | machineId | serviceId | sequence
 * </pre>
 *
 * <p><b>为何不用 {@code record}</b>：本类是需要维护内部状态的<b>执行器</b>
 * （序列号与上次时间戳会随每次调用变化），而非构造后即不变的数据载体。</p>
 *
 * <p><b>为何不依赖框架与自定义异常</b>：它是纯算法工具，构造参数由调用方提供；
 * 时钟回拨与参数越界都属于"调用方只能快速失败"的场景，不需要业务错误码，
 * 因此直接使用标准异常，保持本类零业务依赖、可脱离容器单测。</p>
 *
 * <p>本类实例方法线程安全，可被多线程共享。</p>
 *
 * @author cz
 */
public class SnowFlakeUtil {

    /** 机器标识占用位数。 */
    private static final int MACHINE_ID_BITS = 5;

    /** 服务标识占用位数。 */
    private static final int SERVICE_ID_BITS = 5;

    /** 序列号占用位数。 */
    private static final int SEQUENCE_BITS = 12;

    /** 机器标识上限（含）。 */
    private static final long MAX_MACHINE_ID = (1L << MACHINE_ID_BITS) - 1;

    /** 服务标识上限（含）。 */
    private static final long MAX_SERVICE_ID = (1L << SERVICE_ID_BITS) - 1;

    /** 序列号上限（含）。 */
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    /** 序列号左移位数。 */
    private static final int SEQUENCE_SHIFT = 0;

    /** 服务标识左移位数。 */
    private static final int SERVICE_ID_SHIFT = SEQUENCE_BITS;

    /** 机器标识左移位数。 */
    private static final int MACHINE_ID_SHIFT = SEQUENCE_BITS + SERVICE_ID_BITS;

    /** 时间戳左移位数。 */
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + SERVICE_ID_BITS + MACHINE_ID_BITS;

    /** 默认起始纪元：2022-11-11 00:00:00 (UTC+8)，沿用既有 ID 空间以兼容历史数据。 */
    public static final long DEFAULT_EPOCH_MILLIS = 1668096000000L;

    /** 机器标识。 */
    private final long machineId;

    /** 服务标识。 */
    private final long serviceId;

    /** 起始纪元（毫秒）。 */
    private final long epochMillis;

    /** 时钟来源，便于测试注入。 */
    private final LongSupplier clock;

    /** 上一个生成 ID 所用的毫秒时间，-1 表示尚未生成过。 */
    private long lastTimestamp = -1L;

    /** 当前毫秒内的序列号。 */
    private long sequence = 0L;

    /**
     * 以默认起始纪元与系统时钟构造。
     *
     * @param machineId 机器标识，取值 0~31
     * @param serviceId 服务标识，取值 0~31
     * @throws IllegalArgumentException 任一标识超出取值范围时抛出
     */
    public SnowFlakeUtil(long machineId, long serviceId) {
        this(machineId, serviceId, DEFAULT_EPOCH_MILLIS, System::currentTimeMillis);
    }

    /**
     * 完整构造。
     *
     * @param machineId   机器标识，取值 0~31
     * @param serviceId   服务标识，取值 0~31
     * @param epochMillis 起始纪元（毫秒）
     * @param clock       时钟来源，返回当前毫秒时间戳
     * @throws IllegalArgumentException 任一标识超出取值范围时抛出
     * @throws NullPointerException     时钟为 null 时抛出
     */
    public SnowFlakeUtil(long machineId, long serviceId, long epochMillis, LongSupplier clock) {
        checkRange(machineId, MAX_MACHINE_ID, "machineId");
        checkRange(serviceId, MAX_SERVICE_ID, "serviceId");
        this.machineId = machineId;
        this.serviceId = serviceId;
        this.epochMillis = epochMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 生成下一个唯一 ID。
     *
     * <p>同一毫秒内序列号自增；序列号用尽则自旋等待下一毫秒。
     * 检测到时钟回拨时<b>立即失败</b>而不静默等待，避免生成重复 ID 或掩盖系统时间异常。</p>
     *
     * @return 正数 ID
     * @throws IllegalStateException 检测到时钟回拨时抛出
     */
    public synchronized long nextId() {
        long timestamp = clock.getAsLong();

        if (timestamp < lastTimestamp) {
            throw new IllegalStateException(
                    "检测到时钟回拨，拒绝生成 ID：currentTimestamp=" + timestamp
                            + ", lastTimestamp=" + lastTimestamp
                            + ", 回拨毫秒数=" + (lastTimestamp - timestamp));
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0L) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;

        // 组合四种位段；整体与掩码相与以清零最高位，保证返回正数。
        long id = ((timestamp - epochMillis) << TIMESTAMP_SHIFT)
                | (machineId << MACHINE_ID_SHIFT)
                | (serviceId << SERVICE_ID_SHIFT)
                | (sequence << SEQUENCE_SHIFT);
        return id & Long.MAX_VALUE;
    }

    /**
     * 自旋等待到下一个毫秒。
     *
     * @param lastTimestamp 上一次使用的时间戳
     * @return 大于 {@code lastTimestamp} 的时间戳
     */
    private long waitNextMillis(long lastTimestamp) {
        long timestamp = clock.getAsLong();
        while (timestamp <= lastTimestamp) {
            Thread.onSpinWait();
            timestamp = clock.getAsLong();
        }
        return timestamp;
    }

    /**
     * 校验标识是否落在允许区间内。
     *
     * @param value 待校验值
     * @param max   允许上限（含）
     * @param name  参数名，用于异常信息
     */
    private static void checkRange(long value, long max, String name) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(
                    name + " 超出取值范围：value=" + value + ", 允许区间=[0, " + max + "]");
        }
    }

    /** @return 机器标识 */
    public long getMachineId() {
        return machineId;
    }

    /** @return 服务标识 */
    public long getServiceId() {
        return serviceId;
    }
}
