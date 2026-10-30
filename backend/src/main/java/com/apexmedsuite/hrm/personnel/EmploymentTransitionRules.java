package com.apexmedsuite.hrm.personnel;

/** Guard the current-state projection; rehire requires a separately approved workflow. */
public final class EmploymentTransitionRules {
    private EmploymentTransitionRules() {}
    public static boolean allowed(String currentStatus,String nextStatus,String currentType,String nextType) {
        if (currentStatus == null || nextStatus == null || currentType == null || nextType == null) return false;
        if ("TERMINATED".equals(currentStatus) || "RETIRED".equals(currentStatus)) return false;
        if (currentStatus.equals(nextStatus) && currentType.equals(nextType)) return false;
        return switch (currentStatus) {
            case "ACTIVE" -> switch (nextStatus) {
                case "ACTIVE", "ON_LEAVE", "SUSPENDED", "TERMINATED", "RETIRED" -> true;
                default -> false;
            };
            case "ON_LEAVE" -> switch (nextStatus) {
                case "ACTIVE", "SUSPENDED", "TERMINATED", "RETIRED" -> true;
                default -> false;
            };
            case "SUSPENDED" -> switch (nextStatus) {
                case "ACTIVE", "TERMINATED", "RETIRED" -> true;
                default -> false;
            };
            default -> false;
        };
    }
}
