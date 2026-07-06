package com.voidvault.storage;

import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight 4-bucket latency histogram used by the storage layer to keep
 * a coarse save/load latency distribution without paying the cost of a real
 * HDR histogram. The bucket boundaries ({@code <10ms}, {@code <100ms},
 * {@code <1s}, {@code >=1s}) are fixed and chosen so the daily summary log
 * (see {@link com.voidvault.util.MetricsUtil}) can show meaningful p50/p99
 * numbers without a full quantile sketch.
 *
 * <p>This is intentionally allocation-free on the hot path: every
 * {@link #record(long)} is a single {@link LongAdder#increment()} on the
 * matching bucket. Summary rendering allocates a small StringBuilder once
 * per call.</p>
 */
public final class LatencyHistogram {

    /** Boundary indices — keep in sync with {@link #BUCKET_BOUNDS_NS}. */
    public static final int BUCKET_UNDER_10MS = 0;
    public static final int BUCKET_UNDER_100MS = 1;
    public static final int BUCKET_UNDER_1S = 2;
    public static final int BUCKET_OVER_1S = 3;
    public static final int BUCKET_COUNT = 4;

    /** Upper bounds in nanoseconds, exclusive. The last bucket has no upper bound. */
    static final long[] BUCKET_BOUNDS_NS = {
            10L * 1_000_000L,    //   10ms
            100L * 1_000_000L,   //  100ms
            1_000L * 1_000_000L, // 1000ms (1s)
            Long.MAX_VALUE       //  >=1s (sentinel)
    };

    private final String label;
    private final LongAdder[] buckets;
    private final LongAdder totalCount = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();

    public LatencyHistogram(String label) {
        this.label = label;
        this.buckets = new LongAdder[BUCKET_COUNT];
        for (int i = 0; i < BUCKET_COUNT; i++) {
            this.buckets[i] = new LongAdder();
        }
    }

    /**
     * Record a single observation.
     *
     * @param elapsedNanos elapsed time in nanoseconds; values &lt; 0 are clamped to 0
     */
    public void record(long elapsedNanos) {
        long safe = Math.max(0L, elapsedNanos);
        int idx = bucketIndex(safe);
        buckets[idx].increment();
        totalCount.increment();
        totalNanos.add(safe);
    }

    /**
     * Estimate a percentile by treating bucket counts as if samples are
     * uniformly distributed inside each bucket. The estimate is intentionally
     * approximate — the point of this histogram is to spot "everything is
     * suddenly in the >=1s bucket" without paying the cost of a real sketch.
     */
    public long estimatePercentileMs(double percentile) {
        long count = totalCount.sum();
        if (count == 0) {
            return 0L;
        }
        double target = percentile * count;
        long running = 0L;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            long bucketCount = buckets[i].sum();
            long next = running + bucketCount;
            if (next >= target) {
                long lowerBoundNs = i == 0 ? 0L : BUCKET_BOUNDS_NS[i - 1];
                long upperBoundNs = BUCKET_BOUNDS_NS[i];
                double fractionInBucket = bucketCount == 0 ? 0.0
                        : (target - running) / (double) bucketCount;
                long interpolatedNs = (long) (lowerBoundNs
                        + fractionInBucket * (upperBoundNs - lowerBoundNs));
                // Clamp to the bucket's open upper bound to avoid the
                // sentinel bucket reporting insane values.
                long clamped = Math.min(interpolatedNs,
                        i == BUCKET_COUNT - 1 ? upperBoundNs : (BUCKET_BOUNDS_NS[i] - 1));
                return Math.max(0L, clamped / 1_000_000L);
            }
            running = next;
        }
        return BUCKET_BOUNDS_NS[BUCKET_COUNT - 1] / 1_000_000L;
    }

    /**
     * Build a single-line summary suitable for the daily stats log.
     */
    public String summary() {
        long count = totalCount.sum();
        if (count == 0) {
            return label + ": no samples";
        }
        long p50 = estimatePercentileMs(0.50);
        long p99 = estimatePercentileMs(0.99);
        long avgMs = (totalNanos.sum() / count) / 1_000_000L;
        return String.format("%s p50/p99=%dms/%dms avg=%dms n=%d", label, p50, p99, avgMs, count);
    }

    /**
     * Render every bucket count for the daily stats block. Format:
     * {@code "<10ms=420 <100ms=80 <1s=12 >=1s=0"}.
     */
    public String bucketSummary() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("<10ms=").append(buckets[BUCKET_UNDER_10MS].sum());
        sb.append(" <100ms=").append(buckets[BUCKET_UNDER_100MS].sum());
        sb.append(" <1s=").append(buckets[BUCKET_UNDER_1S].sum());
        sb.append(" >=1s=").append(buckets[BUCKET_OVER_1S].sum());
        return sb.toString();
    }

    /** Total observation count. */
    public long count() {
        return totalCount.sum();
    }

    private static int bucketIndex(long elapsedNanos) {
        for (int i = 0; i < BUCKET_BOUNDS_NS.length; i++) {
            if (elapsedNanos < BUCKET_BOUNDS_NS[i]) {
                return i;
            }
        }
        return BUCKET_COUNT - 1;
    }
}