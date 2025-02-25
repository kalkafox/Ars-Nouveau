package com.hollingsworth.arsnouveau.common.entity;

import com.hollingsworth.arsnouveau.api.entity.ISummon;
import com.hollingsworth.arsnouveau.api.spell.EntitySpellResolver;
import com.hollingsworth.arsnouveau.api.spell.Spell;
import com.hollingsworth.arsnouveau.api.spell.SpellContext;
import com.hollingsworth.arsnouveau.api.util.BlockUtil;
import com.hollingsworth.arsnouveau.client.particle.ParticleColor;
import com.hollingsworth.arsnouveau.client.particle.ParticleLineData;
import com.hollingsworth.arsnouveau.client.particle.ParticleUtil;
import com.hollingsworth.arsnouveau.common.block.tile.IAnimationListener;
import com.hollingsworth.arsnouveau.common.entity.goal.chimera.*;
import com.hollingsworth.arsnouveau.common.network.Networking;
import com.hollingsworth.arsnouveau.common.network.PacketAnimEntity;
import com.hollingsworth.arsnouveau.common.potions.ModPotions;
import com.hollingsworth.arsnouveau.common.potions.SnareEffect;
import com.hollingsworth.arsnouveau.common.spell.augment.AugmentAmplify;
import com.hollingsworth.arsnouveau.common.spell.effect.EffectDelay;
import com.hollingsworth.arsnouveau.common.spell.effect.EffectKnockback;
import com.hollingsworth.arsnouveau.common.spell.effect.EffectLaunch;
import com.hollingsworth.arsnouveau.common.spell.method.MethodTouch;
import com.hollingsworth.arsnouveau.setup.ItemsRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.ai.attributes.AttributeModifierMap;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.controller.MovementController;
import net.minecraft.entity.ai.goal.NearestAttackableTargetGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.monster.MonsterEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.pathfinding.FlyingPathNavigator;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathNavigator;
import net.minecraft.potion.Effect;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.Effects;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.BossInfo;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerBossInfo;
import net.minecraft.world.server.ServerWorld;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;

import java.util.ArrayList;

public class EntityChimera extends MonsterEntity implements IAnimatable, IAnimationListener {
    private final ServerBossInfo bossEvent = (ServerBossInfo)(new ServerBossInfo(this.getDisplayName(), BossInfo.Color.PURPLE, BossInfo.Overlay.PROGRESS)).setDarkenScreen(true).setCreateWorldFog(true).setPlayBossMusic(true);
    public static final DataParameter<Boolean> HAS_SPIKES = EntityDataManager.defineId(EntityChimera.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Boolean> HAS_HORNS = EntityDataManager.defineId(EntityChimera.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Boolean> HAS_WINGS = EntityDataManager.defineId(EntityChimera.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Integer> PHASE = EntityDataManager.defineId(EntityChimera.class, DataSerializers.INT);
    public static final DataParameter<Boolean> DEFENSIVE_MODE = EntityDataManager.defineId(EntityChimera.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Boolean> PHASE_SWAPPING = EntityDataManager.defineId(EntityChimera.class, DataSerializers.BOOLEAN);
    public static final DataParameter<Boolean> IS_FLYING = EntityDataManager.defineId(EntityChimera.class, DataSerializers.BOOLEAN);

    public boolean isRamming;
    public int summonCooldown;
    public int diveCooldown;
    public int spikeCooldown;
    public int ramCooldown;
    public int rageTimer;
    public boolean diving;

    public FlyingPathNavigator flyingNavigator;

    public EntityChimera(EntityType<? extends MonsterEntity> type, World level) {
        super(type, level);
        moveControl = new ChimeraMoveController(this, 10, true);
        maxUpStep = 2.0f;
        setPersistenceRequired();
        initFlyingNavigator();
        rageTimer = 300;
        this.xpReward = 75;
    }

    public EntityChimera(World level) {
        this(ModEntities.WILDEN_BOSS, level);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new SwimGoal(this));
        this.goalSelector.addGoal(5, new ChimeraAttackGoal(this, true));
        this.goalSelector.addGoal(3, new ChimeraSummonGoal(this));
        this.targetSelector.addGoal(0, new NearestAttackableTargetGoal<>(this, PlayerEntity.class, true));
        this.goalSelector.addGoal(1, new ChimeraRageGoal(this));
        this.goalSelector.addGoal(3, new ChimeraRamGoal(this));
        this.goalSelector.addGoal(3, new ChimeraDiveGoal(this));
        this.goalSelector.addGoal(3, new ChimeraSpikeGoal(this));
    }

    @Override
    public void registerControllers(AnimationData animationData) {
        animationData.addAnimationController(new AnimationController<EntityChimera>(this, "walkController", 20, this::groundPredicate));
        animationData.addAnimationController(new AnimationController<EntityChimera>(this, "attackController", 1, this::attackPredicate));
        animationData.addAnimationController(new AnimationController<EntityChimera>(this, "crouchController", 1, this::crouchPredicate));
    }


    private <E extends Entity> PlayState attackPredicate(AnimationEvent event) {
        return PlayState.CONTINUE;
    }


    private <E extends Entity> PlayState crouchPredicate(AnimationEvent event) {
        if (isDefensive()) {
            event.getController().setAnimation(new AnimationBuilder().addAnimation("crouch"));
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }

    private<E extends Entity> PlayState groundPredicate(AnimationEvent e){
        if (!isDefensive() && e.isMoving() && !isFlying()) {
            e.getController().setAnimation(new AnimationBuilder().addAnimation("run"));
            return PlayState.CONTINUE;
        }

        return PlayState.STOP;
    }

    @Override
    public void tick() {
        super.tick();

     //   this.goalSelector.getRunningGoals().forEach(g -> System.out.println(g.getGoal().toString()));
        if(isDefensive())
            setDeltaMovement(0,0,0);
        if(level.isClientSide && isFlying() && random.nextInt(18) == 0){

            this.level.playLocalSound(this.getX(), this.getY(), this.getZ(), SoundEvents.BAT_LOOP, this.getSoundSource(),
                    0.95F + this.random.nextFloat() * 0.05F,
                    0.3f + this.random.nextFloat() * 0.05F,
                    false);

        }
        // If our target is CHEATING, we are going to run the rage goal
        if(!level.isClientSide){

            if(getTarget() != null && this.invulnerableTime == 0 && !this.isDefensive() && !this.isFlying() && this.onGround){
                Path path = getNavigation().createPath(getTarget(), 1);
                if(path == null || !path.canReach() || getTarget().getY() - (this.getY() + 2) >= 3) {
                    rageTimer--;
                }
            }
        }
        if(!isFlying()) {
            setNoGravity(this.isInLava());
        }

        if(!level.isClientSide && this.isInLava() && this.level.getGameTime() % 10 == 0){
            this.addEffect(new EffectInstance(Effects.MOVEMENT_SPEED, 20, 4));
            this.addEffect(new EffectInstance(Effects.DAMAGE_RESISTANCE, 20, 3));
            this.addEffect(new EffectInstance(Effects.FIRE_RESISTANCE, 20));
            this.addEffect(new EffectInstance(Effects.REGENERATION, 20, 3));

        }

        if(this.isFlying()) {
            this.navigation.stop();
            flyingNavigator.tick();
        }
        if(!this.level.isClientSide){
            // Reset our states if something bad happens
            if(this.isFlying()){
                if(this.goalSelector.getRunningGoals().noneMatch(g -> g.getGoal() instanceof ChimeraDiveGoal))
                    setFlying(false);
            }

            if(this.isDefensive()){
                if(this.goalSelector.getRunningGoals().noneMatch(g -> g.getGoal() instanceof ChimeraSpikeGoal))
                    setDefensiveMode(false);
            }
        }

        if(!level.isClientSide) {
            if (summonCooldown > 0)
                summonCooldown--;
            if (diveCooldown > 0) {
                diveCooldown--;
                if(this.isInLava() || this.isInWater())
                    spikeCooldown -= 2;
            }
            if (spikeCooldown > 0) {
                spikeCooldown--;

            }
            if (ramCooldown > 0)
                ramCooldown--;
        }

        if(!level.isClientSide && getPhaseSwapping() && !this.dead){
            if(this.getHealth() < this.getMaxHealth()){
                this.heal(2.0f);
            }else{
                this.removeAllEffects();
                this.setPhaseSwapping(false);
                for(LivingEntity e : level.getEntitiesOfClass(PlayerEntity.class, new AxisAlignedBB(this.blockPosition()).inflate(5))){

                    EntitySpellResolver resolver = new EntitySpellResolver(new SpellContext(
                            new Spell.Builder().add(MethodTouch.INSTANCE)
                                    .add(EffectLaunch.INSTANCE).add(AugmentAmplify.INSTANCE, 2).add(EffectDelay.INSTANCE).add(EffectKnockback.INSTANCE).add(AugmentAmplify.INSTANCE, 2).build()
                    , this));
                    resolver.onCastOnEntity(ItemStack.EMPTY, this, e, Hand.MAIN_HAND);
                }
                getRandomUpgrade();
                gainPhaseBuffs();
                ParticleUtil.spawnPoof((ServerWorld) level, blockPosition().above());
                if(getPhase() == 1)
                    rageTimer = 200;
            }
        }

        if(getPhaseSwapping() && level.isClientSide){
            spawnPhaseParticles(blockPosition(), level, getPhase());
        }
    }

    public static void spawnPhaseParticles(BlockPos pos, World level, int multiplier){
        if(!level.isClientSide)
            return;
        int baseAge =  40;
        float scaleAge = (float) ParticleUtil.inRange(0.1, 0.2);
        for(int i =0; i< 10 * (Math.min(1, multiplier)); i++){
            Vector3d particlePos = new Vector3d(pos.getX(), pos.getY() + 1, pos.getZ()).add(0.5, 0.5, 0.5);
            particlePos = particlePos.add(ParticleUtil.pointInSphere().multiply(3.0, 3.0, 3.0));
            level.addParticle(ParticleLineData.createData(ParticleColor.makeRandomColor(255, 255, 255, level.random),scaleAge, baseAge + level.random.nextInt(20)) ,
                    particlePos.x(), particlePos.y(), particlePos.z(),
                    pos.getX() + 0.5  , pos.getY() +0.5 , pos.getZ()+ 0.5);
        }
    }

    protected void dropCustomDeathLoot(DamageSource p_213333_1_, int p_213333_2_, boolean p_213333_3_) {
        super.dropCustomDeathLoot(p_213333_1_, p_213333_2_, p_213333_3_);
        ItemEntity itementity = this.spawnAtLocation(ItemsRegistry.WILDEN_TRIBUTE);
        if (itementity != null) {
            itementity.setExtendedLifetime();
        }
    }

    @Override
    protected boolean shouldDropLoot() {
        return true;
    }

    protected void playStepSound(BlockPos p_180429_1_, BlockState p_180429_2_) {
        this.playSound(SoundEvents.POLAR_BEAR_STEP, 0.15F + (random.nextFloat() * 0.3f), 0.8F + random.nextFloat() * 0.1f);
    }

    @Override
    public boolean isSilent() {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource p_184601_1_) {
        return SoundEvents.POLAR_BEAR_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.POLAR_BEAR_DEATH;
    }

    public void gainPhaseBuffs(){
        this.addEffect(new EffectInstance(Effects.REGENERATION, 100 + 100 * getPhase(), 3));
        this.addEffect(new EffectInstance(Effects.DAMAGE_BOOST, 300 + 300 * getPhase(), getPhase()));
        this.addEffect(new EffectInstance(Effects.MOVEMENT_SPEED, 300 + 300 * getPhase(), getPhase()));
    }

    public void resetCooldowns(){
        spikeCooldown = 0;
        ramCooldown = 0;
        diveCooldown = 0;
        summonCooldown = 0;
    }

    @Override
    public void heal(float p_70691_1_) {
        this.setHealth(this.getHealth() + p_70691_1_);
    }

    public boolean canDive(){
        return !isRamming && diveCooldown <= 0 && hasWings() && !getPhaseSwapping() && !isFlying() && this.onGround && !isDefensive();
    }

    public boolean canSpike(){
        return !isRamming && spikeCooldown <= 0 && hasSpikes() && !getPhaseSwapping() && !isFlying() && this.onGround && this.getTarget() != null;
    }

    public boolean canRam(){
        return !isRamming && ramCooldown <= 0 && hasHorns() && !getPhaseSwapping() && !isFlying() && !isDefensive() && getTarget() != null && getTarget().isOnGround() && this.isOnGround();
    }

    public boolean canSummon(){
        return !isRamming && getTarget() != null && summonCooldown <= 0 && !isFlying() && !getPhaseSwapping() && !isDefensive() && this.onGround;
    }

    public boolean canAttack(){
        return !isRamming && getTarget() != null && this.getHealth() >= 1 && !this.getPhaseSwapping() && !isFlying() && !isDefensive();
    }

    public boolean canRage(){
        return !getPhaseSwapping() && !isRamming;
    }

    @Override
    public boolean isDeadOrDying() {
        return getPhase() > 3;
    }

    public void getRandomUpgrade(){
        ArrayList<Integer> upgrades = new ArrayList<>();
        if(!this.hasWings())
            upgrades.add(0);
        if(!this.hasSpikes())
            upgrades.add(1);
        if(!this.hasHorns())
            upgrades.add(2);
        if(upgrades.isEmpty())
            return;
        int upgrade = upgrades.get(random.nextInt(upgrades.size()));
        switch(upgrade){
            case 0:
                setWings(true);
                return;
            case 1:
                setSpikes(true);
                return;
            case 2:
                setHorns(true);
                return;
        }
    }


    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source == DamageSource.CACTUS || source == DamageSource.SWEET_BERRY_BUSH || source == DamageSource.IN_WALL || source == DamageSource.LAVA || source == DamageSource.DROWN)
            return false;
        if (source.msgId.equals("cold"))
            amount = amount / 2;
        if(this.getPhaseSwapping())
            return false;

        Entity entity = source.getEntity();
        if(entity instanceof LivingEntity && !entity.equals(this)){
            if(isDefensive() && !source.msgId.equals("thorns")){
                if(!source.isBypassArmor() && BlockUtil.distanceFrom(entity.position, position) <= 3){
                    entity.hurt(DamageSource.thorns(this), 6.0f);
                }
            }

            LivingEntity entity1 = (LivingEntity) entity;
            // Omit our summoned sources that might aggro or accidentally hurt us
            if(entity1 instanceof WildenStalker || entity1 instanceof WildenGuardian || entity instanceof WildenHunter
                    || (entity instanceof ISummon && ((ISummon) entity).getOwnerID() != null && ((ISummon) entity).getOwnerID().equals(this.getUUID()))
            || (entity1 instanceof SummonWolf && ((SummonWolf) entity1).isWildenSummon))
                return false;
        }

        if(isDefensive())
            return false;

        boolean res = super.hurt(source, amount);
        if(!this.level.isClientSide && this.getHealth() <= 0.0 && getPhase() < 3){
            this.setPhaseSwapping(true);
            this.setPhase(this.getPhase() + 1);
            this.getNavigation().stop();
            this.setHealth(1.0f);
            Networking.sendToNearby(level, this, new PacketAnimEntity(this.getId(), EntityChimera.Animations.HOWL.ordinal()));
            this.setFlying(false);
            this.setDefensiveMode(false);
            this.isRamming = false;
            this.dead = false;
        }
        if(!this.level.isClientSide && this.getHealth() <= 0.0 && getPhase() == 3){
            this.setPhase(4);
            super.die(source);
        }

        return res;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        this.bossEvent.setPercent(this.getHealth() / this.getMaxHealth());
    }

    protected boolean canRide(Entity p_184228_1_) {
        return false;
    }

    @Override
    public void checkDespawn() {
        if (this.level.getDifficulty() == Difficulty.PEACEFUL && this.shouldDespawnInPeaceful()) {
            this.remove();
        } else {
            this.noActionTime = 0;
        }
    }


    public void startSeenByPlayer(ServerPlayerEntity p_184178_1_) {
        super.startSeenByPlayer(p_184178_1_);
        this.bossEvent.addPlayer(p_184178_1_);
    }

    public void stopSeenByPlayer(ServerPlayerEntity p_184203_1_) {
        super.stopSeenByPlayer(p_184203_1_);
        this.bossEvent.removePlayer(p_184203_1_);
    }

    public boolean canChangeDimensions() {
        return false;
    }

    public boolean canBeAffected(EffectInstance instance) {
        Effect effect = instance.getEffect();
        if(instance.getEffect() instanceof SnareEffect)
            return false;

        if(effect == Effects.MOVEMENT_SLOWDOWN)
            instance = new EffectInstance(instance.getEffect(), 1, 0);

        if(effect == ModPotions.GRAVITY_EFFECT)
            instance = new EffectInstance(instance.getEffect(), Math.min(instance.getDuration(), 100), 0);
        return super.canBeAffected(instance);
    }

    public int getCooldownModifier(){
        return 300/(getPhase() + 1);
    }

    AnimationFactory factory = new AnimationFactory(this);

    @Override
    public AnimationFactory getFactory() {
        return factory;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(HAS_HORNS, false);
        this.entityData.define(HAS_SPIKES, false);
        this.entityData.define(HAS_WINGS, false);
        this.entityData.define(PHASE, 1);
        this.entityData.define(DEFENSIVE_MODE, false);
        this.entityData.define(PHASE_SWAPPING, false);
        this.entityData.define(IS_FLYING, false);
    }
    public boolean isFlying(){
        return entityData.get(IS_FLYING);
    }

    public void setFlying(boolean flying){
        entityData.set(IS_FLYING, flying);
    }
    public boolean hasHorns(){
        return entityData.get(HAS_HORNS);
    }

    public void setHorns(boolean hasHorns){
        entityData.set(HAS_HORNS, hasHorns);
    }

    public boolean hasSpikes(){
        return entityData.get(HAS_SPIKES);
    }

    public void setSpikes(boolean hasSpikes){
        entityData.set(HAS_SPIKES, hasSpikes);
    }

    public boolean hasWings(){
        return entityData.get(HAS_WINGS);
    }

    public void setWings(boolean hasWings){
        entityData.set(HAS_WINGS, hasWings);
    }

    public boolean isDefensive(){
        return entityData.get(DEFENSIVE_MODE);
    }

    public void setDefensiveMode(boolean defensiveMode){
        entityData.set(DEFENSIVE_MODE, defensiveMode);
    }

    public int getPhase(){
        return entityData.get(PHASE);
    }

    public void setPhase(int phase){
        entityData.set(PHASE, phase);
    }

    public boolean getPhaseSwapping(){
        return entityData.get(PHASE_SWAPPING);
    }

    public void setPhaseSwapping(boolean swapping){
        entityData.set(PHASE_SWAPPING, swapping);
    }

    @Override
    public void load(CompoundNBT tag) {
        super.load(tag);
        setHorns(tag.getBoolean("horns"));
        setSpikes(tag.getBoolean("spikes"));
        setWings(tag.getBoolean("wings"));
        setPhase(tag.getInt("phase"));
        setDefensiveMode(tag.getBoolean("defensive"));
        setPhaseSwapping(tag.getBoolean("swapping"));
        summonCooldown = tag.getInt("summonCooldown");
        diveCooldown = tag.getInt("diveCooldown");
        spikeCooldown = tag.getInt("spikeCooldown");
        ramCooldown = tag.getInt("ramCooldown");
        rageTimer = tag.getInt("rage");
    }

    @Override
    public boolean save(CompoundNBT tag) {
        tag.putBoolean("spikes", hasSpikes());
        tag.putBoolean("horns", hasHorns());
        tag.putBoolean("wings", hasWings());
        tag.putInt("phase", getPhase());
        tag.putBoolean("defensive", isDefensive());
        tag.putInt("summonCooldown", summonCooldown);
        tag.putInt("diveCooldown", diveCooldown);
        tag.putInt("spikeCooldown", spikeCooldown);
        tag.putInt("ramCooldown", ramCooldown);
        tag.putBoolean("swapping", getPhaseSwapping());
        tag.putInt("rage", rageTimer);
        return super.save(tag);
    }

    @Override
    public void startAnimation(int arg) {
        try{
            if(arg == Animations.ATTACK.ordinal()){
                AnimationController controller = this.factory.getOrCreateAnimationData(this.hashCode()).getAnimationControllers().get("attackController");

                if(controller.getCurrentAnimation() != null && (controller.getCurrentAnimation().animationName.equals("claw_swipe"))) {
                    return;
                }
                controller.markNeedsReload();
                controller.setAnimation(new AnimationBuilder().addAnimation("claw_swipe", false).addAnimation("idle"));
            }

            if(arg == Animations.HOWL.ordinal()){
                AnimationController controller = this.factory.getOrCreateAnimationData(this.hashCode()).getAnimationControllers().get("attackController");

                if(controller.getCurrentAnimation() != null && (controller.getCurrentAnimation().animationName.equals("howl"))) {
                    return;
                }
                controller.markNeedsReload();
                controller.setAnimation(new AnimationBuilder().addAnimation("howl", false).addAnimation("idle"));
            }

            if(arg == Animations.CHARGE.ordinal()){
                AnimationController controller = this.factory.getOrCreateAnimationData(this.hashCode()).getAnimationControllers().get("attackController");
                controller.markNeedsReload();
                controller.setAnimation(new AnimationBuilder().addAnimation("ready_charge", false).addAnimation("charge", true));
            }

            if(arg == Animations.FLYING.ordinal()){
                AnimationController controller = this.factory.getOrCreateAnimationData(this.hashCode()).getAnimationControllers().get("attackController");
                controller.markNeedsReload();
                controller.setAnimation(new AnimationBuilder().addAnimation("flying", true));
            }
            if(arg == Animations.DIVE_BOMB.ordinal()){
                AnimationController controller = this.factory.getOrCreateAnimationData(this.hashCode()).getAnimationControllers().get("attackController");
                controller.markNeedsReload();
                controller.setAnimation(new AnimationBuilder().addAnimation("divebomb", true));
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @Override
    protected void checkFallDamage(double p_184231_1_, boolean p_184231_3_, BlockState p_184231_4_, BlockPos p_184231_5_) {
        super.checkFallDamage(p_184231_1_, p_184231_3_, p_184231_4_, p_184231_5_);
        if(hasWings())
            this.fallDistance = 0;
        this.fallDistance = Math.min(fallDistance, 10);
    }

    public enum Animations{
        ATTACK,
        HOWL,
        CHARGE,
        FLYING,
        DIVE_BOMB
    }

    @Override
    public PathNavigator getNavigation() {
        return this.isFlying() ? flyingNavigator : super.getNavigation();
    }

    public void initFlyingNavigator(){
        FlyingPathNavigator flyingpathnavigator = new FlyingPathNavigator(this, level);
        flyingpathnavigator.setCanOpenDoors(true);
        flyingpathnavigator.setCanFloat(false);
        flyingpathnavigator.setCanPassDoors(true);
        this.flyingNavigator = flyingpathnavigator;
    }

    public Vector3d orbitOffset = Vector3d.ZERO;

    public static class ChimeraMoveController extends MovementController{

        private final int maxTurn;
        private final boolean hoversInPlace;

        public ChimeraMoveController(EntityChimera p_i225710_1_, int maxTurn, boolean hoversInPlace) {
            super(p_i225710_1_);
            this.maxTurn = maxTurn;
            this.hoversInPlace = hoversInPlace;
        }

        @Override
        public void tick() {
            EntityChimera chimera = (EntityChimera) this.mob;
            if(chimera.isFlying()) {
                if (chimera.diving) {
                    diveTick();
                } else {
                    flyTick();
                }
            }else{
                super.tick();
            }
        }
        // Copy from FlyingMovementController
        public void flyTick(){
            if (this.operation == MovementController.Action.MOVE_TO) {
                this.operation = MovementController.Action.WAIT;
                this.mob.setNoGravity(true);
                double d0 = this.wantedX - this.mob.getX();
                double d1 = this.wantedY - this.mob.getY();
                double d2 = this.wantedZ - this.mob.getZ();
                double d3 = d0 * d0 + d1 * d1 + d2 * d2;
                if (d3 < (double)2.5000003E-7F) {
                    this.mob.setYya(0.0F);
                    this.mob.setZza(0.0F);
                    return;
                }

                float f = (float)(MathHelper.atan2(d2, d0) * (double)(180F / (float)Math.PI)) - 90.0F;
                this.mob.yRot = this.rotlerp(this.mob.yRot, f, 90.0F);
                float f1;
                if (this.mob.isOnGround()) {
                    f1 = (float)(this.speedModifier * this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
                } else {
                    f1 = (float)(this.speedModifier * this.mob.getAttributeValue(Attributes.FLYING_SPEED));
                }

                this.mob.setSpeed(f1);
                double d4 = (double)MathHelper.sqrt(d0 * d0 + d2 * d2);
                float f2 = (float)(-(MathHelper.atan2(d1, d4) * (double)(180F / (float)Math.PI)));
                this.mob.xRot = this.rotlerp(this.mob.xRot, f2, (float)this.maxTurn);
                this.mob.setYya(d1 > 0.0D ? f1 : -f1);
            } else {
                if (!this.hoversInPlace) {
                    this.mob.setNoGravity(false);
                }

                this.mob.setYya(0.0F);
                this.mob.setZza(0.0F);
            }

        }

        public void diveTick(){
            EntityChimera mob = (EntityChimera) this.mob;
            double posX = mob.getX();
            double posY = mob.getY();
            double posZ = mob.getZ();
            double motionX = mob.getDeltaMovement().x;
            double motionY = mob.getDeltaMovement().y;
            double motionZ = mob.getDeltaMovement().z;
            BlockPos dest = new BlockPos(mob.orbitOffset);

            double speedMod = 1.3;
            if (dest.getX() != 0 || dest.getY() != 0 || dest.getZ() != 0){
                double targetX = dest.getX()+0.5;
                double targetY = dest.getY()+0.5;
                double targetZ = dest.getZ()+0.5;
                Vector3d targetVector = new Vector3d(targetX-posX,targetY-posY,targetZ-posZ);
                double length = targetVector.length();
                targetVector = targetVector.scale(0.3/length);
                double weight  = 0;
                if (length <= 3){
                    weight = 0.9*((3.0-length)/3.0);
                }

                motionX = (0.9-weight)*motionX+(speedMod + weight)*targetVector.x;
                motionY = (0.9-weight)*motionY+(speedMod + weight)*targetVector.y;
                motionZ = (0.9-weight)*motionZ+(speedMod + weight)*targetVector.z;
            }
            mob.setDeltaMovement(motionX, motionY, motionZ);
            faceBlock(new BlockPos(mob.orbitOffset), mob);
        }
    }

    public static void faceBlock(final BlockPos block, final LivingEntity citizen) {
        final double xDifference = block.getX() - citizen.blockPosition().getX();
        final double zDifference = block.getZ() - citizen.blockPosition().getZ();
        final double yDifference = block.getY() - (citizen.blockPosition().getY() + citizen.getEyeHeight());
        final double squareDifference = Math.sqrt(xDifference * xDifference + zDifference * zDifference);
        final double intendedRotationYaw = (Math.atan2(zDifference, xDifference) * 180.0D / Math.PI) - 90.0;
        final double intendedRotationPitch = -(Math.atan2(yDifference, squareDifference) * 180.0D / Math.PI);
        citizen.yRot = (float) (updateRotation(citizen.yRot, intendedRotationYaw, 360.0) % 360.0F);
        citizen.xRot = (float) (updateRotation(citizen.xRot, intendedRotationPitch, 360.0) % 360.0F);
    }
    public static double updateRotation(final double currentRotation, final double intendedRotation, final double maxIncrement) {
        double wrappedAngle = MathHelper.wrapDegrees(intendedRotation - currentRotation);
        if (wrappedAngle > maxIncrement) {
            wrappedAngle = maxIncrement;
        }
        if (wrappedAngle < -maxIncrement) {
            wrappedAngle = -maxIncrement;
        }
        return currentRotation + wrappedAngle;
    }

    @Override
    protected float getWaterSlowDown() {
        return 1.0f;
    }

    public static AttributeModifierMap.MutableAttribute getModdedAttributes(){
        return MobEntity.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 225D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6F)
                .add(Attributes.ATTACK_KNOCKBACK, 1.0D)
                .add(Attributes.ATTACK_DAMAGE, 8D)
                .add(Attributes.ARMOR, 6D)
                .add(Attributes.FOLLOW_RANGE, 100D)
                .add(Attributes.FLYING_SPEED,0.4f);
    }
}
