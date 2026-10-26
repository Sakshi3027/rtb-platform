package com.sakshichavan.rtb.bidder.budget;

import java.time.Clock;

class InMemoryBudgetSourceTest extends BudgetSourceContract {

    @Override
    protected BudgetSource newSource(Clock clock, PacingPolicy pacing) {
        return new InMemoryBudgetSource(clock, pacing);
    }
}
