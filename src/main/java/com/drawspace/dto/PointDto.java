package com.drawspace.dto;

/**
 * World-coordinate point of a stroke/shape (CONTRACT §7). Doubles; up to 4000 points
 * per stroke, exactly 2 endpoints for shapes.
 */
public record PointDto(double x, double y) {
}
