package com.sakshichavan.rtb.billing;

/** One poll: records read, settlements newly recorded, duplicates ignored, and malformed or invalid records skipped. */
public record BatchResult(int records, int inserted, int duplicates, int rejected) {

    static final BatchResult EMPTY = new BatchResult(0, 0, 0, 0);
}
