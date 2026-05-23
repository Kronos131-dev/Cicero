package org.example.service.strategy;

public class ScoringFactory {
    public static ScoringStrategy getStrategy(String internalRole) {
        return switch (internalRole) {
            case "TOP" -> new TopStrategy();
            case "JUNGLE" -> new JungleStrategy();
            case "MIDDLE" -> new MidStrategy();
            case "BOTTOM" -> new AdcStrategy();
            case "SUPPORT" -> new SupportStrategy();
            default -> new TopStrategy();
        };
    }
}