package com.sakshichavan.rtb.common.openrtb;

import java.util.List;

/** Publisher site. cat holds IAB content categories, used for targeting. */
public record Site(String id, String domain, List<String> cat) {

    public Site {
        cat = (cat == null) ? List.of() : List.copyOf(cat);
    }
}
