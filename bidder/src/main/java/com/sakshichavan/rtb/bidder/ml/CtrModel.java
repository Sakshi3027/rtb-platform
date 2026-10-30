package com.sakshichavan.rtb.bidder.ml;

import com.sakshichavan.rtb.common.features.ImpressionFeatures;

import java.util.List;

/** Predicts the probability of a click for each row, in the same order. Implementations must be thread-safe. */
public interface CtrModel {

    double[] predict(List<ImpressionFeatures> rows);
}
