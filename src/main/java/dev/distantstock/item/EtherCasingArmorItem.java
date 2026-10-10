package dev.distantstock.item;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.armor.BacktankUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

/** The four-piece Distant Casing suit. */
public final class EtherCasingArmorItem extends ArmorItem {
    private static final String AIR_INITIALIZED = "DistantStockAirInitialized";
    private static final String MEDIUM = "DistantStockMedium";

    /** The two fluids the suit runs on, as ids. Stored on the piece, compared as strings. */
    public static final String ETHER_MEDIUM = "distantstock:ether";
    public static final String MOLTEN_AMETHYST_MEDIUM = "distantstock:molten_amethyst";

    public EtherCasingArmorItem(Type type, Item.Properties properties) {
        super(ModArmorMaterials.ETHER_CASING, type, properties);
    }

    /** The Create backtank value is the suit's internal working-medium reserve. */
    public static int reserve(ItemStack stack) {
        return BacktankUtil.getAir(stack);
    }

    public static int maxReserve(ItemStack stack) {
        return BacktankUtil.maxAir(stack);
    }

    public static boolean hasReserve(ItemStack stack) {
        return reserve(stack) > 0;
    }

    /** Fluid id currently occupying the internal reserve; blank when empty/untyped. */
    public static String medium(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString(MEDIUM);
    }

    public static void setMedium(ItemStack stack, String id) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, current -> {
            CompoundTag tag = current.copyTag();
            if (id == null || id.isBlank()) tag.remove(MEDIUM);
            else tag.putString(MEDIUM, id);
            return CustomData.of(tag);
        });
    }

    /**
     * Which of the suit's two media a fluid would supply, or null when it can supply neither.
     *
     * <p>Kept here rather than at each feeder because there are three of them -- the tanks a player
     * carries, the tower's trickle, and whatever comes next -- and a second copy of "which fluids
     * count" is how the armour and the tank end up disagreeing about what a medium is.
     */
    public static String mediumIdOf(FluidStack fluid) {
        if (fluid.isEmpty()) return null;
        if (fluid.is(dev.distantstock.fluid.ModFluids.ETHER.get())) return ETHER_MEDIUM;
        if (fluid.is(dev.distantstock.fluid.ModFluids.MOLTEN_AMETHYST.get())) return MOLTEN_AMETHYST_MEDIUM;
        return null;
    }

    public static void addReserve(ItemStack stack, int amount, String mediumId) {
        if (!(stack.getItem() instanceof EtherCasingArmorItem) || amount <= 0) return;
        int before = reserve(stack);
        int next = Math.min(maxReserve(stack), before + amount);
        if (next <= before) return;
        if (before <= 0 || medium(stack).isBlank()) setMedium(stack, mediumId);
        stack.set(AllDataComponents.BACKTANK_AIR, next);
    }

    public static int consumeReserve(ItemStack stack, int amount) {
        if (amount <= 0) return 0;
        int before = reserve(stack);
        int used = Math.min(before, amount);
        if (used <= 0) return 0;
        int left = before - used;
        stack.set(AllDataComponents.BACKTANK_AIR, left);
        if (left <= 0) setMedium(stack, "");
        return used;
    }

    public static boolean hasFullSet(LivingEntity entity) {
        return entity != null
                && entity.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.ETHER_CASING_HELMET.get())
                && entity.getItemBySlot(EquipmentSlot.CHEST).is(ModItems.ETHER_CASING_CHESTPLATE.get())
                && entity.getItemBySlot(EquipmentSlot.LEGS).is(ModItems.ETHER_CASING_LEGGINGS.get())
                && entity.getItemBySlot(EquipmentSlot.FEET).is(ModItems.ETHER_CASING_BOOTS.get());
    }

    /** Raw player intent. The server applies the 0.5 s arming delay and hit disruption. */
    public static boolean wantsCloak(LivingEntity entity) {
        // Use the synchronized sneak flag, not the resolved CROUCHING pose. The pose is derived
        // later and is not a reliable cross-client/server trigger (flying players are the obvious
        // example). The shared shift flag is exactly what the player actually pressed and is sent
        // to the server/other clients by vanilla.
        return entity != null && entity.isShiftKeyDown() && hasFullSet(entity);
    }

    @Override
    public boolean canElytraFly(ItemStack stack, LivingEntity entity) {
        return getType() == Type.CHESTPLATE && BacktankUtil.hasAirRemaining(stack);
    }

    @Override
    public boolean elytraFlightTick(ItemStack stack, LivingEntity entity, int flightTicks) {
        if (!entity.level().isClientSide && flightTicks % 10 == 0) {
            BacktankUtil.consumeAir(entity, stack, 1);
        }
        return BacktankUtil.hasAirRemaining(stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean selected) {
        super.inventoryTick(stack, level, entity, slotId, selected);
        // Lazy seed: capacity reads Create's server config, so it cannot be known at registration
        // time. The old implementation treated every empty tank as "brand new", which meant a
        // survival chestplate refilled itself the tick after the player exhausted it. Keep an
        // explicit one-shot marker instead: old saves migrate on first tick, then zero really means
        // empty until a charger/tower puts air back in.
        if (level.isClientSide) return;
        if (!(entity instanceof LivingEntity living)) return;
        int max = BacktankUtil.maxAir(stack);
        int current = BacktankUtil.getAir(stack);
        boolean creative = entity instanceof net.minecraft.world.entity.player.Player player
                && player.getAbilities().instabuild;
        if (creative) {
            if (current < max) stack.set(AllDataComponents.BACKTANK_AIR, max);
            markAirInitialized(stack);
            return;
        }

        if (!airInitialized(stack)) {
            if (current <= 0 && max > 0) {
                stack.set(AllDataComponents.BACKTANK_AIR, max);
            }
            markAirInitialized(stack);
        }
        if (BacktankUtil.getAir(stack) <= 0 && !medium(stack).isBlank()) {
            setMedium(stack, "");
        }
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return BacktankUtil.getAir(stack) < BacktankUtil.maxAir(stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int max = BacktankUtil.maxAir(stack);
        return max == 0 ? 0 : Math.min(13, Math.round(13f * BacktankUtil.getAir(stack) / max));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return medium(stack).contains("molten_amethyst") ? 0xB56CFF : 0x6ED2C8;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.cloak")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.stealth")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.reach")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.charged")
                .withStyle(ChatFormatting.AQUA));
        // What this particular piece adds. The numbers are baked into the lang lines, the same way
        // the reach line above already states its own; EtherCasingStatsCheck is what keeps them
        // honest against the constants in EtherCasingStatsServer.
        if (getType() == Type.HELMET) {
            tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.helmet_bonus")
                    .withStyle(ChatFormatting.BLUE));
            tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.goggles")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (getType() == Type.CHESTPLATE) {
            tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.chestplate_bonus")
                    .withStyle(ChatFormatting.BLUE));
        }
        if (getType() == Type.LEGGINGS) {
            tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.leggings_bonus")
                    .withStyle(ChatFormatting.BLUE));
        }
        if (getType() == Type.BOOTS) {
            tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.boots_bonus")
                    .withStyle(ChatFormatting.BLUE));
        }
        tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.reserve",
                        reserve(stack), maxReserve(stack))
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.wear")
                .withStyle(ChatFormatting.DARK_GRAY));
        if (getType() == Type.CHESTPLATE) {
            tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.flight")
                    .withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.boost")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("item.distantstock.ether_casing_armor.recharge")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * The suit has two states and this is what selects between them. A plain one is colourless glass
     * and the player reads as unarmoured from across a field; an enchanted one is the same shell lit
     * through with ether and is blue throughout. See the generator's CLEAR and IMBUED for the numbers.
     *
     * <p>Enchantment is shown by a change of texture rather than by vanilla's glint because on this
     * armour the glint cannot draw at all. {@code HumanoidArmorLayer} asks for
     * {@code RenderType.armorEntityGlint()}, which tests depth with {@code EQUAL_DEPTH_TEST} and
     * carries {@code VIEW_OFFSET_Z_LAYERING}; vanilla armour carries the same layering state, so the
     * two sit at exactly the same depth and the test passes. Our layer is swapped to
     * {@code entityTranslucent}, which has no layering state, so the armour and its glint land at
     * different depths and not one pixel of glint is ever drawn. Restoring it would mean a custom
     * render type -- and a scrolling full-model overlay on a suit that is a quarter coverage would
     * become the loudest thing on it at every distance, which is the opposite of the point.
     */
    @Override
    public ResourceLocation getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot,
                                            ArmorMaterial.Layer layer, boolean innerModel) {
        int phase = entity instanceof LivingEntity living ? EtherCasingCloakState.get(living) : 0;
        phase = Math.max(0, Math.min(EtherCasingCloakState.PHASES, phase));
        String state = stack.hasFoil() ? "_imbued" : "";
        return ResourceLocation.fromNamespaceAndPath("distantstock",
                "textures/models/armor/ether_casing_layer_" + (innerModel ? 2 : 1)
                        + "_phase_" + phase + state + ".png");
    }

    private static boolean airInitialized(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.getBoolean(AIR_INITIALIZED);
    }

    private static void markAirInitialized(ItemStack stack) {
        if (airInitialized(stack)) return;
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, current -> {
            CompoundTag tag = current.copyTag();
            tag.putBoolean(AIR_INITIALIZED, true);
            return CustomData.of(tag);
        });
    }
}
