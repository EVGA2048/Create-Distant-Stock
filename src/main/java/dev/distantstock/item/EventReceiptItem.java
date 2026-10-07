package dev.distantstock.item;

import dev.distantstock.event.EventRegistry;
import dev.distantstock.event.EventText;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Printed incident slip from a Distant Stock Logger. Printing one acknowledges the event. */
public final class EventReceiptItem extends Item {
    private static final String ROOT = "DistantStockEventReceipt";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public EventReceiptItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(EventRegistry.Record event, long printedAt) {
        ItemStack stack = new ItemStack(ModItems.EVENT_RECEIPT.get());
        CompoundTag data = new CompoundTag();
        data.putUUID("EventId", event.id());
        data.putLong("OccurredAt", event.createdAt());
        data.putLong("PrintedAt", printedAt);
        data.putString("Severity", event.severity().name());
        data.putString("Code", event.code());
        data.putString("SourceType", event.sourceType());
        data.putString("SourceId", event.sourceId());
        data.putString("Detail", event.detail());
        data.putInt("Count", event.count());
        if (event.createFrequency() != null) data.putUUID("CreateFrequency", event.createFrequency());
        if (event.distantNetworkId() != null) data.putUUID("DistantNetwork", event.distantNetworkId());
        CompoundTag root = new CompoundTag();
        root.put(ROOT, data);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return stack;
    }

    public static Optional<Receipt> read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!root.contains(ROOT)) return Optional.empty();
        CompoundTag data = root.getCompound(ROOT);
        if (!data.hasUUID("EventId")) return Optional.empty();
        EventRegistry.Severity severity;
        try {
            severity = EventRegistry.Severity.valueOf(data.getString("Severity"));
        } catch (IllegalArgumentException broken) {
            severity = EventRegistry.Severity.INFO;
        }
        return Optional.of(new Receipt(data.getUUID("EventId"), data.getLong("OccurredAt"),
                data.getLong("PrintedAt"), severity, data.getString("Code"),
                data.getString("SourceType"), data.getString("SourceId"), data.getString("Detail"),
                data.hasUUID("CreateFrequency") ? data.getUUID("CreateFrequency") : null,
                data.hasUUID("DistantNetwork") ? data.getUUID("DistantNetwork") : null,
                Math.max(1, data.getInt("Count"))));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.addAll(lines(stack));
    }

    public static List<Component> lines(ItemStack stack) {
        Receipt receipt = read(stack).orElse(null);
        if (receipt == null) return List.of();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(TIME.format(Instant.ofEpochMilli(receipt.occurredAt())))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.distantstock.event_receipt.printed_at",
                TIME.format(Instant.ofEpochMilli(receipt.printedAt()))).withStyle(ChatFormatting.DARK_GRAY));
        ChatFormatting severityColor = switch (receipt.severity()) {
            case INFO -> ChatFormatting.AQUA;
            case WARN -> ChatFormatting.GOLD;
            case ERROR -> ChatFormatting.RED;
        };
        lines.add(Component.literal(receipt.severity().name() + " / ")
                .append(EventText.title(receipt.code()))
                .withStyle(severityColor));
        lines.add(Component.translatable("item.distantstock.event_receipt.source",
                        receipt.sourceType() + " / " + receipt.sourceId())
                .withStyle(ChatFormatting.DARK_GRAY));
        if (receipt.createFrequency() != null) {
            lines.add(Component.translatable("item.distantstock.event_receipt.create_network",
                    receipt.createFrequency().toString()).withStyle(ChatFormatting.DARK_AQUA));
        }
        if (receipt.distantNetworkId() != null) {
            lines.add(Component.translatable("item.distantstock.event_receipt.distant_network",
                    receipt.distantNetworkId().toString()).withStyle(ChatFormatting.BLUE));
        }
        if (!receipt.detail().isBlank()) {
            lines.add(Component.translatable("item.distantstock.event_receipt.detail",
                            EventText.detail(receipt.code(), receipt.detail()))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (receipt.count() > 1) {
            lines.add(Component.translatable("item.distantstock.event_receipt.count", receipt.count())
                    .withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable("item.distantstock.event_receipt.event",
                eventLabel(receipt.severity(), receipt.eventId())).withStyle(ChatFormatting.DARK_AQUA));
        lines.add(Component.translatable("item.distantstock.event_receipt.acknowledged")
                .withStyle(ChatFormatting.DARK_GREEN));
        return lines;
    }

    public static String eventLabel(EventRegistry.Severity severity, UUID id) {
        String level = switch (severity) {
            case INFO -> "I";
            case WARN -> "W";
            case ERROR -> "E";
        };
        return "DS-" + level + "-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    public record Receipt(UUID eventId, long occurredAt, long printedAt,
                          EventRegistry.Severity severity, String code,
                          String sourceType, String sourceId, String detail,
                          UUID createFrequency, UUID distantNetworkId, int count) {
    }
}
