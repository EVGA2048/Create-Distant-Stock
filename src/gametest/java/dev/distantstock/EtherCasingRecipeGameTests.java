package dev.distantstock;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The four suit recipes are 5 wide and 7 tall, which is larger than a crafting table and larger than
 * anything Create ships.
 *
 * <p>It works, but only because Create calls {@code ShapedRecipePattern.setCraftingSize(9, 9)} on
 * mod construction -- vanilla's limit is 3x3 and a pattern over it is rejected at load with no more
 * than a log line. Nothing in the build checks recipe JSON, so a typo here would ship silently and
 * the suit would simply be uncraftable. Hence this: a recipe that failed to parse never reaches the
 * recipe manager, so looking it up is the whole test.
 *
 * <p>No arena is needed; the recipes live in the level's data, not in the world.
 */
@GameTestHolder("distantstock")
@PrefixGameTestTemplate(false)
public final class EtherCasingRecipeGameTests {
    private static final int WIDTH = 7;
    private static final int HEIGHT = 5;
    private static final String[] PIECES = {"helmet", "chestplate", "leggings", "boots"};

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void suitRecipesLoadAtTheirFullSize(GameTestHelper h) {
        for (String piece : PIECES) {
            String path = "ether_casing_" + piece;
            var id = ResourceLocation.fromNamespaceAndPath(DistantStock.MODID, path);
            var holder = h.getLevel().getRecipeManager().byKey(id);
            h.assertTrue(holder.isPresent(),
                    path + " is not in the recipe manager -- it failed to parse");
            if (!(holder.get().value() instanceof ShapedRecipe recipe)) {
                h.fail(path + " is not a shaped recipe");
                return;
            }
            h.assertTrue(recipe.pattern.width() == WIDTH && recipe.pattern.height() == HEIGHT,
                    path + " is " + recipe.pattern.width() + "x" + recipe.pattern.height()
                            + ", expected " + WIDTH + "x" + HEIGHT);
        }
        h.succeed();
    }

    private EtherCasingRecipeGameTests() {
    }
}
