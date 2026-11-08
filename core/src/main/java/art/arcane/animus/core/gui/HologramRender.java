package art.arcane.animus.core.gui;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.presentation.DisplayEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Lays out one hologram for one viewer as Gloss does: every text row in one text display whose bottom sits on the
 * anchor, object lines in blank rows of their own height, and interaction boxes centred on the text block.
 */
final class HologramRender {
    private static final UUID NONE = new UUID(0, 0);

    private HologramRender() {}

    /** An interaction box as the server checks it: a box {@code width} wide and deep and {@code height} tall above its feet. */
    record Box(double x, double y, double z, double width, double height) {
        /** Whether a ray from the eye along the look direction passes through the box grown by {@code slack}, within reach. */
        boolean hit(double[] eye, double[] look, double reach, double slack) {
            double half = width / 2 + slack;
            double[] min = {x - half, y - slack, z - half}, max = {x + half, y + height + slack, z + half};
            double near = 0, far = reach;
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(look[axis]) < 1.0E-9) { if (eye[axis] < min[axis] || eye[axis] > max[axis]) return false; continue; }
                double a = (min[axis] - eye[axis]) / look[axis], b = (max[axis] - eye[axis]) / look[axis];
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
                if (near > far) return false;
            }
            return true;
        }
    }

    record Render(Map<String, DisplayEntity> entities, Map<String, Box> boxes, int rows) {}

    /** {@code fill} fills the viewer's placeholders in each rendered text line, before it is coloured. */
    static Render build(HologramDocument document, List<HologramDocument.Line> lines, HoloParts.Style style, GuiExpression.Scope scope,
                        UnaryOperator<String> fill, double x, double y, double z) {
        double rowHeight = HologramDocument.ROW_HEIGHT * style.scaleY();
        boolean objects = lines.stream().anyMatch((HologramDocument.Line line) -> !(line instanceof HologramDocument.TextLine));
        List<String> rows = new ArrayList<>();
        record Placed(HologramDocument.Line line, int first, int count) {}
        List<Placed> placed = new ArrayList<>();
        for (HologramDocument.Line line : lines) {
            boolean shown = line.show().test(scope);
            if (line instanceof HologramDocument.TextLine text) {
                String rendered = RichText.colorize(fill.apply(text.text().render(scope)));
                String[] parts = rendered.split("\n", -1);
                if (shown) for (String part : parts) rows.add(part);
                else if (objects) for (int index = 0; index < parts.length; index++) rows.add("");
                continue;
            }
            double height = switch (line) {
                case HologramDocument.ItemLine item -> item.scale();
                case HologramDocument.HeadLine head -> head.scale();
                case HologramDocument.BlockLine block -> block.scale();
                case HologramDocument.MobLine mob -> mob.scale();
                case HologramDocument.TextLine ignored -> 0;
            };
            int count = Math.max(1, (int) Math.ceil(height / rowHeight - 1.0E-9));
            if (shown) placed.add(new Placed(line, rows.size(), count));
            for (int index = 0; index < count; index++) rows.add(" ");
        }
        Map<String, DisplayEntity> entities = new LinkedHashMap<>();
        Map<String, Box> boxes = new LinkedHashMap<>();
        double total = rows.size() * rowHeight;
        float yaw = document.yaw(), pitch = document.pitch();
        if (!rows.isEmpty()) {
            DisplayEntity.Style display = style.display(1, 0, 0, 0, 0);
            entities.put("text", new DisplayEntity(0, NONE, style.text(String.join("\n", rows)), x, y, z, yaw, pitch, display));
        }
        for (int index = 0; index < placed.size(); index++) {
            Placed object = placed.get(index);
            double centre = y + total - (object.first() + object.count() / 2.0) * rowHeight;
            String slot = "line:" + index;
            DisplayEntity.Style base = new DisplayEntity.Style(style.billboard(), 1, 1, 1, 0, 0, 0, style.brightness(), style.viewRange(), 0, 0, -1, 0);
            switch (object.line()) {
                case HologramDocument.ItemLine item -> {
                    float scale = (float) item.scale();
                    entities.put(slot, new DisplayEntity(0, NONE, new DisplayEntity.Item(GuiScreen.item(item.icon(), scope), 0), x, centre, z, yaw, pitch,
                            base.scaled(scale, scale, scale).translated(0, 0, scale / 2 + 0.02f)));
                }
                case HologramDocument.HeadLine head -> {
                    float scale = (float) head.scale();
                    ScreenFrame.Item skull = new ScreenFrame.Item(new NpcItem("minecraft:player_head", 1, Optional.empty(), Optional.empty()), Optional.empty(), List.of(),
                            Optional.of(head.player().render(scope).trim()));
                    entities.put(slot, new DisplayEntity(0, NONE, new DisplayEntity.Item(skull, 8), x, centre, z, yaw, pitch,
                            base.scaled(scale, scale, scale).translated(0, 0, scale / 2 + 0.02f)));
                }
                case HologramDocument.BlockLine block -> {
                    float scale = (float) block.scale();
                    entities.put(slot, new DisplayEntity(0, NONE, new DisplayEntity.Block(block.block()), x, centre, z, yaw, pitch,
                            base.billboard(DisplayEntity.Billboard.FIXED).scaled(scale, scale, scale).translated(-scale / 2, -scale / 2, -scale / 2)));
                }
                case HologramDocument.MobLine mob -> entities.put(slot, new DisplayEntity(0, NONE, new DisplayEntity.Mob(mob.type()), x, centre - mob.scale() / 2, z,
                        yaw, 0, DisplayEntity.Style.DEFAULT));
                case HologramDocument.TextLine ignored -> {}
            }
        }
        if (!document.actions().isEmpty() && !rows.isEmpty()) {
            HologramDocument.Hitbox hitbox = document.hitbox();
            double middle = y + total / 2;
            if (!hitbox.perLine()) {
                double height = Math.min(64, Math.max(hitbox.height(), total));
                box(entities, boxes, "hitbox", x, middle - height / 2, z, hitbox.width(), height);
            } else for (int row = 0; row < rows.size(); row++) {
                double centre = middle + hitbox.height() * ((rows.size() - 1) / 2.0 - row);
                box(entities, boxes, "hitbox:" + row, x, centre - hitbox.height() / 2, z, hitbox.width(), hitbox.height());
            }
        }
        return new Render(entities, boxes, rows.size());
    }

    private static void box(Map<String, DisplayEntity> entities, Map<String, Box> boxes, String slot, double x, double y, double z, double width, double height) {
        entities.put(slot, new DisplayEntity(0, NONE, new DisplayEntity.Hitbox((float) width, (float) height), x, y, z, 0, 0, DisplayEntity.Style.DEFAULT));
        boxes.put(slot, new Box(x, y, z, width, height));
    }
}
