package com.bazaar.inventory.api;

public record CampaignReconcile(int quota, int remaining, int held, int committed, boolean balanced) {
}
