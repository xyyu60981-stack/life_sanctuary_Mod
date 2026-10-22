package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Objects;

/** Internal debit proposal, never a standalone mutation or a callback into species code. */
record PlantReproductionDebit(PlantIndividualState expected, String key, int amount) {
    PlantReproductionDebit {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(key);
        if (key.isBlank() || amount <= 0 || expected.reproduction().getOrDefault(key, 0) < amount)
            throw new IllegalArgumentException("Invalid or unavailable reproduction cost");
    }

    PlantIndividualState after() {
        var budgets = new HashMap<>(expected.reproduction());
        // Keep zero: exhausted resources must not become indistinguishable from uninitialized ones.
        budgets.put(key, budgets.get(key) - amount);
        return expected.withReproduction(budgets);
    }
}
