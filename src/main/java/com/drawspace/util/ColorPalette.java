package com.drawspace.util;

import java.util.List;

/**
 * Ordered participant color palette per CONTRACT §2.
 * Assign the first color not used by active participants, else index = activeCount % 10.
 */
public final class ColorPalette {

    public static final List<String> COLORS = List.of(
            "#EF4444", "#F59E0B", "#8B5CF6", "#06B6D4", "#EC4899",
            "#22C55E", "#F97316", "#3B82F6", "#D946EF", "#10B981");

    private ColorPalette() {
    }

    /**
     * @param usedColors  colors currently assigned to ACTIVE participants in the room
     * @param activeCount current number of active participants (fallback index base)
     * @return the assigned color
     */
    public static String pickColor(List<String> usedColors, int activeCount) {
        for (String color : COLORS) {
            if (!usedColors.contains(color)) {
                return color;
            }
        }
        return COLORS.get(Math.floorMod(activeCount, COLORS.size()));
    }
}
