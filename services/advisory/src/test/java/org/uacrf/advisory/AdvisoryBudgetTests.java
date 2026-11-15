package org.uacrf.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdvisoryBudgetTests {
  private static AdvisoryBudget.Allocation allocation(
      AdvisoryBudget.Role role, String profile, int tokens, int costUnits) {
    return new AdvisoryBudget.Allocation(role, profile, tokens, costUnits);
  }

  @Test
  void acceptsExactLimitsAndCopiesAllocations() {
    var allocations = new ArrayList<>(List.of(
        allocation(AdvisoryBudget.Role.ANALYST, "analyst", 100, 0),
        allocation(AdvisoryBudget.Role.REVIEWER, "reviewer", 200, 5)));
    var budget = new AdvisoryBudget(2, 300, 5, allocations);
    allocations.clear();

    assertEquals(2, budget.allocations().size());
    assertThrows(UnsupportedOperationException.class, () -> budget.allocations().clear());
  }

  @Test
  void rejectsCallTokenAndCostOverAllocation() {
    var allocations = List.of(
        allocation(AdvisoryBudget.Role.ANALYST, "analyst", 100, 4),
        allocation(AdvisoryBudget.Role.REVIEWER, "reviewer", 200, 5));

    assertThrows(IllegalArgumentException.class, () -> new AdvisoryBudget(1, 300, 9, allocations));
    assertThrows(IllegalArgumentException.class, () -> new AdvisoryBudget(2, 299, 9, allocations));
    assertThrows(IllegalArgumentException.class, () -> new AdvisoryBudget(2, 300, 8, allocations));
    assertThrows(IllegalArgumentException.class, () -> new AdvisoryBudget(2, 300, -1, allocations));
  }

  @Test
  void rejectsDuplicateRolesProfilesAndInvalidAllocations() {
    var analyst = allocation(AdvisoryBudget.Role.ANALYST, "analyst", 1, 0);
    assertThrows(IllegalArgumentException.class, () -> new AdvisoryBudget(2, 2, 0,
        List.of(analyst, allocation(AdvisoryBudget.Role.ANALYST, "other", 1, 0))));
    assertThrows(IllegalArgumentException.class, () -> new AdvisoryBudget(2, 2, 0,
        List.of(analyst, allocation(AdvisoryBudget.Role.REVIEWER, "analyst", 1, 0))));
    assertThrows(IllegalArgumentException.class,
        () -> allocation(AdvisoryBudget.Role.CRITIC, "invalid profile", 1, 0));
    assertThrows(IllegalArgumentException.class,
        () -> allocation(AdvisoryBudget.Role.CRITIC, "critic", 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new AdvisoryBudget(1, 1, 0, List.of()));
  }

  @Test
  void rejectsSumsBeyondLimitsWithoutIntegerOverflow() {
    var allocations = List.of(
        allocation(AdvisoryBudget.Role.ANALYST, "a", 1, 1_000_000_000),
        allocation(AdvisoryBudget.Role.REVIEWER, "b", 1, 1_000_000_000),
        allocation(AdvisoryBudget.Role.CRITIC, "c", 1, 1_000_000_000));
    assertThrows(IllegalArgumentException.class,
        () -> new AdvisoryBudget(3, 3, 1_000_000_000, allocations));
  }

  @Test
  void enforcesContractUpperBoundsAndAllowsTheirExactValues() {
    var allocation = allocation(AdvisoryBudget.Role.ANALYST, "analyst", 10_000_000, 1_000_000_000);
    var allocations = List.of(allocation);
    assertEquals(100, new AdvisoryBudget(100, 10_000_000, 1_000_000_000, allocations).maxCalls());
    assertThrows(IllegalArgumentException.class, () -> new AdvisoryBudget(101, 10_000_000, 1_000_000_000, allocations));
    assertThrows(IllegalArgumentException.class, () -> new AdvisoryBudget(100, 10_000_001, 1_000_000_000, allocations));
    assertThrows(IllegalArgumentException.class, () -> new AdvisoryBudget(100, 10_000_000, 1_000_000_001, allocations));
    assertThrows(IllegalArgumentException.class,
        () -> allocation(AdvisoryBudget.Role.ANALYST, "analyst", 10_000_001, 0));
    assertThrows(IllegalArgumentException.class,
        () -> allocation(AdvisoryBudget.Role.ANALYST, "analyst", 1, 1_000_000_001));
  }
}
