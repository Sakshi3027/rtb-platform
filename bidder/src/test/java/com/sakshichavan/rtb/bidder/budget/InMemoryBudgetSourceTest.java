package com.sakshichavan.rtb.bidder.budget;

class InMemoryBudgetSourceTest extends BudgetSourceContract {

    @Override
    protected BudgetSource newSource() {
        return new InMemoryBudgetSource();
    }
}
