package com.hollingsworth.arsnouveau.common.spell.effect;

import com.hollingsworth.arsnouveau.GlyphLib;
import com.hollingsworth.arsnouveau.api.ANFakePlayer;
import com.hollingsworth.arsnouveau.api.spell.*;
import com.hollingsworth.arsnouveau.api.util.SpellUtil;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentAOE;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentPierce;
import com.hollingsworth.arsnouveau.common.spell.method.MethodTouch;
import net.minecraft.block.BlockState;
import net.minecraft.block.material.Material;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.FakePlayer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

public class EffectPlaceBlock extends AbstractEffect {
    public static EffectPlaceBlock INSTANCE = new EffectPlaceBlock();

    private EffectPlaceBlock() {
        super(GlyphLib.EffectPlaceBlockID, "Place Block");
    }

    @Override
    public void onResolveBlock(BlockRayTraceResult rayTraceResult, World world, @Nullable LivingEntity shooter, SpellStats spellStats, SpellContext spellContext) {
        List<BlockPos> posList = SpellUtil.calcAOEBlocks(shooter, rayTraceResult.getBlockPos(), rayTraceResult, spellStats);
        BlockRayTraceResult result = rayTraceResult;
        for(BlockPos pos1 : posList) {
            BlockPos hitPos = result.isInside() ? pos1 : pos1.relative(result.getDirection());
            if(spellContext.castingTile instanceof IPlaceBlockResponder){
                ItemStack stack = ((IPlaceBlockResponder) spellContext.castingTile).onPlaceBlock();
                if(stack.isEmpty() || !(stack.getItem() instanceof BlockItem))
                    return;

                BlockItem item = (BlockItem) stack.getItem();
                FakePlayer fakePlayer = ANFakePlayer.getPlayer((ServerWorld) world);
                fakePlayer.setItemInHand(Hand.MAIN_HAND, stack);

                // Special offset for touch
                boolean isTouch = spellContext.getSpell().recipe.get(0) instanceof MethodTouch;
                BlockState blockTargetted = isTouch ? world.getBlockState(hitPos.relative(result.getDirection().getOpposite())) : world.getBlockState(hitPos.relative(result.getDirection()));
                if(blockTargetted.getMaterial() != Material.AIR)
                    continue;
                // Special offset because we are placing a block against the face we are looking at (in the case of touch)
                Direction direction = isTouch ? result.getDirection().getOpposite() : result.getDirection();
                BlockItemUseContext context = BlockItemUseContext.at(new BlockItemUseContext(new ItemUseContext(fakePlayer, Hand.MAIN_HAND, result)),
                        hitPos.relative(direction), direction);
                item.place(context);
            }else if(shooter instanceof IPlaceBlockResponder){
                ItemStack stack = ((IPlaceBlockResponder) shooter).onPlaceBlock();
                if(stack.isEmpty() || !(stack.getItem() instanceof BlockItem))
                    return;
                BlockItem item = (BlockItem) stack.getItem();
                if(world.getBlockState(hitPos).getMaterial() != Material.AIR){
                    result = new BlockRayTraceResult(result.getLocation().add(0, 1, 0), Direction.UP, result.getBlockPos(),false);
                }
                attemptPlace(world, stack, item, result);
            }else if(shooter instanceof PlayerEntity){
                PlayerEntity playerEntity = (PlayerEntity) shooter;
                NonNullList<ItemStack> list =  playerEntity.inventory.items;
                if(!world.getBlockState(hitPos).getMaterial().isReplaceable())
                    continue;
                for(int i = 0; i < 9; i++){
                    ItemStack stack = list.get(i);
                    if(stack.getItem() instanceof BlockItem && world instanceof ServerWorld){
                        BlockItem item = (BlockItem)stack.getItem();

                        BlockRayTraceResult resolveResult = new BlockRayTraceResult(new Vector3d(hitPos.getX(), hitPos.getY(), hitPos.getZ()), result.getDirection(), hitPos, false);
                        ActionResultType resultType = attemptPlace(world, stack, item, resolveResult);
                        if(ActionResultType.FAIL != resultType)
                            break;
                    }
                }
            }
        }
    }

    @Override
    public boolean wouldSucceed(RayTraceResult rayTraceResult, World world, LivingEntity shooter, List<AbstractAugment> augments) {
        return nonAirBlockSuccess(rayTraceResult, world);
    }

    public static ActionResultType attemptPlace(World world, ItemStack stack, BlockItem item, BlockRayTraceResult result){
        FakePlayer fakePlayer = ANFakePlayer.getPlayer((ServerWorld) world);
        fakePlayer.setItemInHand(Hand.MAIN_HAND, stack);
        BlockItemUseContext context = BlockItemUseContext.at(new BlockItemUseContext(new ItemUseContext(fakePlayer, Hand.MAIN_HAND, result)), result.getBlockPos(), result.getDirection());
        return item.place(context);
    }

    @Override
    public int getManaCost() {
        return 10;
    }

    @Nullable
    @Override
    public Item getCraftingReagent() {
        return Items.DISPENSER;
    }

    @Nonnull
    @Override
    public Set<AbstractAugment> getCompatibleAugments() {
        return augmentSetOf(AugmentAOE.INSTANCE, AugmentPierce.INSTANCE);
    }

    @Override
    public String getBookDescription() {
        return "Places blocks from the casters inventory. If a player is casting this, this spell will place blocks from the hot bar first.";
    }

    @Nonnull
    @Override
    public Set<SpellSchool> getSchools() {
        return setOf(SpellSchools.MANIPULATION);
    }
}
