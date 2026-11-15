package org.uacrf.advisory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

record AdvisoryBudget(int maxCalls, int maxTokens, int maxCostUnits, List<Allocation> allocations) {
  private static final int MAX_TOKENS = 10_000_000;
  private static final int MAX_COST_UNITS = 1_000_000_000;

  AdvisoryBudget {
    if (maxCalls < 1 || maxCalls > 100 || maxTokens < 1 || maxTokens > MAX_TOKENS
        || maxCostUnits < 0 || maxCostUnits > MAX_COST_UNITS) {
      throw new IllegalArgumentException("Invalid advisory budget limits.");
    }
    if (allocations == null || allocations.isEmpty() || allocations.size() > 3
        || allocations.stream().anyMatch(allocation -> allocation == null)) {
      throw new IllegalArgumentException("One to three allocations are required.");
    }
    allocations = List.copyOf(allocations);
    if (allocations.size() > maxCalls) {
      throw new IllegalArgumentException("Allocations exceed the call budget.");
    }

    Set<Role> roles = new HashSet<>();
    Set<String> profiles = new HashSet<>();
    long tokens = 0;
    long costUnits = 0;
    for (Allocation allocation : allocations) {
      if (!roles.add(allocation.role()) || !profiles.add(allocation.agentProfileId())) {
        throw new IllegalArgumentException("Each allocation needs a distinct role and profile.");
      }
      tokens += allocation.tokens();
      costUnits += allocation.costUnits();
    }
    if (tokens > maxTokens || costUnits > maxCostUnits) {
      throw new IllegalArgumentException("Allocations exceed the token or cost budget.");
    }
  }

  enum Role {
    ANALYST, REVIEWER, CRITIC
  }

  record Allocation(Role role, String agentProfileId, int tokens, int costUnits) {
    Allocation {
      if (role == null || agentProfileId == null
          || !agentProfileId.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")
          || tokens < 1 || tokens > MAX_TOKENS
          || costUnits < 0 || costUnits > MAX_COST_UNITS) {
        throw new IllegalArgumentException("Invalid advisory allocation.");
      }
    }
  }
}
