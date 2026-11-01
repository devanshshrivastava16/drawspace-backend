package com.drawspace.dto;

import java.time.Instant;
import java.util.List;

/**
 * WS payload for {@code /app/room/{code}/draw} (CONTRACT §7).
 *
 * <p>Deliberately a <b>mutable POJO</b> (not a record): the server enriches the accepted
 * copy with {@code participantId}, {@code displayName}, {@code participantColor},
 * {@code sequenceNumber} and {@code serverTimestamp} (§4) before broadcasting to
 * {@code /topic/room/{code}/draw}, while the full field map is persisted as
 * {@code drawing_events.event_data} (§6).
 * Clients send only the fields relevant to the op type; unknown enums / type-tool
 * mismatches are rejected to /user/queue/errors (§7).</p>
 *
 * <p>Validation ranges enforced by the service layer (§7): brushSize 1..100,
 * opacity 0..1, points ≤ 4000 (doubles, world coords), text ≤ 2000 chars,
 * font ≤ 64 chars, fontSize 8..200, sticker ≤ 64 chars, width/height &gt;0 ≤ 100000,
 * sides 3..20, scale 0.05..50, rotation -360..360, name ≤ 60 chars, payload ≤ 512 KB.</p>
 */
public class DrawingOperationMessage {

    /** Client-generated idempotency key (UUID string); dedupe key in drawing_events (§6). */
    private String eventId;
    /** EventType name (validated against com.drawspace.domain.EventType). */
    private String type;
    private String objectId;
    private String layerId;
    /** ToolType name (validated against com.drawspace.domain.ToolType). */
    private String tool;
    private String color;
    private String fillColor;
    private Integer brushSize;
    private Double opacity;
    /** LineStyle name. */
    private String lineStyle;
    private List<PointDto> points;
    private String text;
    private String font;
    private Integer fontSize;
    private Boolean bold;
    private Boolean italic;
    /** TextAlign name. */
    private String align;
    private String bgColor;
    private String sticker;
    private Double x;
    private Double y;
    private Double width;
    private Double height;
    private Integer sides;
    private Double scale;
    private Double rotation;
    private TransformDto transform;
    /** UUID string of the targeted event (UNDO/REDO only). */
    private String targetEventId;
    /** Layer name for LAYER_CREATE / LAYER_UPDATE rename (≤ 60 chars). */
    private String name;
    /** Full ordered layerId list for LAYER_REORDER. */
    private List<String> order;
    private Boolean visible;
    private Boolean locked;
    private String canvasBg;
    private Long clientTimestamp;

    // ------------------------------------------------------------------
    // Server-side enrichment (never trusted from the client, §4/§6)
    // ------------------------------------------------------------------
    private String participantId;
    private String displayName;
    /** Participant's assigned palette color (CONTRACT §2/§4) — must NOT overwrite the op's own {@code color}. */
    private String participantColor;
    private Long sequenceNumber;
    private Instant serverTimestamp;

    public DrawingOperationMessage() {
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getObjectId() {
        return objectId;
    }

    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    public String getLayerId() {
        return layerId;
    }

    public void setLayerId(String layerId) {
        this.layerId = layerId;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getFillColor() {
        return fillColor;
    }

    public void setFillColor(String fillColor) {
        this.fillColor = fillColor;
    }

    public Integer getBrushSize() {
        return brushSize;
    }

    public void setBrushSize(Integer brushSize) {
        this.brushSize = brushSize;
    }

    public Double getOpacity() {
        return opacity;
    }

    public void setOpacity(Double opacity) {
        this.opacity = opacity;
    }

    public String getLineStyle() {
        return lineStyle;
    }

    public void setLineStyle(String lineStyle) {
        this.lineStyle = lineStyle;
    }

    public List<PointDto> getPoints() {
        return points;
    }

    public void setPoints(List<PointDto> points) {
        this.points = points;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getFont() {
        return font;
    }

    public void setFont(String font) {
        this.font = font;
    }

    public Integer getFontSize() {
        return fontSize;
    }

    public void setFontSize(Integer fontSize) {
        this.fontSize = fontSize;
    }

    public Boolean getBold() {
        return bold;
    }

    public void setBold(Boolean bold) {
        this.bold = bold;
    }

    public Boolean getItalic() {
        return italic;
    }

    public void setItalic(Boolean italic) {
        this.italic = italic;
    }

    public String getAlign() {
        return align;
    }

    public void setAlign(String align) {
        this.align = align;
    }

    public String getBgColor() {
        return bgColor;
    }

    public void setBgColor(String bgColor) {
        this.bgColor = bgColor;
    }

    public String getSticker() {
        return sticker;
    }

    public void setSticker(String sticker) {
        this.sticker = sticker;
    }

    public Double getX() {
        return x;
    }

    public void setX(Double x) {
        this.x = x;
    }

    public Double getY() {
        return y;
    }

    public void setY(Double y) {
        this.y = y;
    }

    public Double getWidth() {
        return width;
    }

    public void setWidth(Double width) {
        this.width = width;
    }

    public Double getHeight() {
        return height;
    }

    public void setHeight(Double height) {
        this.height = height;
    }

    public Integer getSides() {
        return sides;
    }

    public void setSides(Integer sides) {
        this.sides = sides;
    }

    public Double getScale() {
        return scale;
    }

    public void setScale(Double scale) {
        this.scale = scale;
    }

    public Double getRotation() {
        return rotation;
    }

    public void setRotation(Double rotation) {
        this.rotation = rotation;
    }

    public TransformDto getTransform() {
        return transform;
    }

    public void setTransform(TransformDto transform) {
        this.transform = transform;
    }

    public String getTargetEventId() {
        return targetEventId;
    }

    public void setTargetEventId(String targetEventId) {
        this.targetEventId = targetEventId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getOrder() {
        return order;
    }

    public void setOrder(List<String> order) {
        this.order = order;
    }

    public Boolean getVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
    }

    public Boolean getLocked() {
        return locked;
    }

    public void setLocked(Boolean locked) {
        this.locked = locked;
    }

    public String getCanvasBg() {
        return canvasBg;
    }

    public void setCanvasBg(String canvasBg) {
        this.canvasBg = canvasBg;
    }

    public Long getClientTimestamp() {
        return clientTimestamp;
    }

    public void setClientTimestamp(Long clientTimestamp) {
        this.clientTimestamp = clientTimestamp;
    }

    public String getParticipantId() {
        return participantId;
    }

    public void setParticipantId(String participantId) {
        this.participantId = participantId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getParticipantColor() {
        return participantColor;
    }

    public void setParticipantColor(String participantColor) {
        this.participantColor = participantColor;
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(Long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public Instant getServerTimestamp() {
        return serverTimestamp;
    }

    public void setServerTimestamp(Instant serverTimestamp) {
        this.serverTimestamp = serverTimestamp;
    }
}
