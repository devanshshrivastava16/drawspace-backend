package com.drawspace.dto;

/**
 * Absolute geometry of an OBJECT_TRANSFORM op (CONTRACT §7): position + scale + rotation,
 * applied as absolute values (not deltas) during replay (§8).
 */
public record TransformDto(double x, double y, double scale, double rotation) {
}
