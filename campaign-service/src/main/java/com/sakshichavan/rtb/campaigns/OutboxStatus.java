package com.sakshichavan.rtb.campaigns;

/** How far a publisher has got through the change log: lag is the number of changes not yet published. */
public record OutboxStatus(String publisher, long publishedRevision, long latestRevision, long lag) {
}
