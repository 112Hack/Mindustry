package mindustry.ai.types;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

public class CommandAI extends AIController{
    protected static final int maxCommandQueueSize = 50;
    protected static final Vec2 vecOut = new Vec2(), vecMovePos = new Vec2();
    protected static final boolean[] noFound = {false};

    public Seq<Position> commandQueue = new Seq<>(5);
    public @Nullable Vec2 targetPos;
    public @Nullable Teamc attackTarget;
    /** Group of units that were all commanded to reach the same point.. */
    public @Nullable UnitGroup group;
    public int groupIndex = 0;
    /** All encountered unreachable buildings of this AI. Why a sequence? Because contains() is very rarely called on it. */
    public IntSeq unreachableBuildings = new IntSeq(8);

    protected boolean stopAtTarget, stopWhenInRange;
    protected Vec2 lastTargetPos;
    protected int pathId = -1;

    /** Stance, usually related to firing mode. */
    public UnitStance stance = UnitStance.shoot;
    /** Current command this unit is following. */
    public @Nullable UnitCommand command;
    /** Current controller instance based on command. */
    protected @Nullable AIController commandController;
    /** Last command type assigned. Used for detecting command changes. */
    protected @Nullable UnitCommand lastCommand;

    public UnitCommand currentCommand(){
        return command == null ? UnitCommand.moveCommand : command;
    }

    /** Attempts to assign a command to this unit. If not supported by the unit type, does nothing. */
    public void command(UnitCommand command){
        if(Structs.contains(unit.type.commands, command)){
            //clear old state.
            unit.mineTile = null;
            unit.clearBuilding();
            this.command = command;
        }
    }

    @Override
    public boolean isLogicControllable(){
        return !hasCommand();
    }

    public boolean isAttacking(){
        return target != null && unit.within(target, unit.range() + 10f);
    }

    @Override
    public void updateUnit(){
        //this should not be possible
        if(stance == UnitStance.stop) stance = UnitStance.shoot;

        //pursue the target if relevant
        if(stance == UnitStance.pursueTarget && target != null && attackTarget == null && targetPos == null){
            commandTarget(target, false);
        }

        //remove invalid targets
        if(commandQueue.any()){
            commandQueue.removeAll(e -> e instanceof Healthc h && !h.isValid());
        }

        //assign defaults
        if(command == null && unit.type.commands.length > 0){
            command = unit.type.defaultCommand == null ? unit.type.commands[0] : unit.type.defaultCommand;
        }

        //update command controller based on index.
        var curCommand = command;
        if(lastCommand != curCommand){
            lastCommand = curCommand;
            commandController = (curCommand == null ? null : curCommand.controller.get(unit));
        }

        //use the command controller if it is provided, and bail out.
        if(commandController != null){
            if(commandController.unit() != unit) commandController.unit(unit);
            commandController.updateUnit();
        }else{
            defaultBehavior();
            //boosting control is not supported, so just don't.
            unit.updateBoosting(false);
        }
    }

    public void clearCommands(){
        commandQueue.clear();
        targetPos = null;
        attackTarget = null;
    }

    public void defaultBehavior(){

        if(!net.client() && unit instanceof Payloadc pay){
            //auto-drop everything
            if(command == UnitCommand.unloadPayloadCommand && pay.hasPayload()){
                Call.payloadDropped(unit, unit.x, unit.y);
            }

            //try to pick up what's under it
            if(command == UnitCommand.loadUnitsCommand){
                Unit target = Units.closest(unit.team, unit.x, unit.y, unit.type.hitSize * 2f, u -> u.isAI() && u != unit && u.isGrounded() && pay.canPickup(u) && u.within(unit, u.hitSize + unit.hitSize));
                if(target != null){
                    Call.pickedUnitPayload(unit, target);
                }
            }

            //try to pick up a block
            if(command == UnitCommand.loadBlocksCommand && (targetPos == null || unit.within(targetPos, 1f))){
                Building build = world.buildWorld(unit.x, unit.y);

                if(build != null && state.teams.canInteract(unit.team, build.team)){
                    //pick up block's payload
                    Payload current = build.getPayload();
                    if(current != null && pay.canPickupPayload(current)){
                        Call.pickedBuildPayload(unit, build, false);
                        //pick up whole building directly
                    }else if(build.block.buildVisibility != BuildVisibility.hidden && build.canPickup() && pay.canPickup(build)){
                        Call.pickedBuildPayload(unit, build, true);
                    }
                }
            }
        }

        //acquiring naval targets isn't supported yet, so use the fallback dumb AI
        if(unit.team.isAI() && unit.team.rules().rtsAi && unit.type.naval){
            if(fallback == null) fallback = new GroundAI();

            if(fallback.unit() != unit) fallback.unit(unit);
            fallback.updateUnit();
            return;
        }

        updateVisuals();
        //only autotarget if the unit supports it
        if((targetPos == null || nearAttackTarget(unit.x, unit.y, unit.range())) || unit.type.autoFindTarget){
            updateTargeting();
        }else if(attackTarget == null){
            //if the unit does not have an attack target, is currently moving, and does not have autotargeting, stop attacking stuff
            target = null;
            for(var mount : unit.mounts){
                if(mount.weapon.controllable){
                    mount.target = null;
                }
            }
        }

        if(attackTarget != null && invalid(attackTarget)){
            attackTarget = null;
            targetPos = null;
        }

        //move on to the next target
        if(attackTarget == null && targetPos == null){
            finishPath();
        }

        if(attackTarget != null){
            if(targetPos == null){
                targetPos = new Vec2();
                lastTargetPos = targetPos;
            }
            targetPos.set(attackTarget);

            if(unit.isGrounded() && attackTarget instanceof Building build && build.tile.solid() && unit.pathType() != Pathfinder.costLegs && stance != UnitStance.ram){
                Tile best = build.findClosestEdge(unit, Tile::solid);
                if(best != null){
                    targetPos.set(best);
                }
            }
        }

        if(targetPos != null){
            boolean move = true, isFinalPoint = commandQueue.size == 0;
            vecOut.set(targetPos);
            vecMovePos.set(targetPos);

            if(group != null && group.valid && groupIndex < group.units.size){
                vecMovePos.add(group.positions[groupIndex * 2], group.positions[groupIndex * 2 + 1]);
            }

            //TODO: should the unit stop when it finds a target?
            if(stance == UnitStance.patrol && target != null && unit.within(target, unit.type.range - 2f)){
                move = false;
            }

            if(unit.isGrounded() && stance != UnitStance.ram){
                move = Vars.controlPath.getPathPosition(unit, pathId, vecMovePos, vecOut, noFound);
                //we've reached the final point if the returned coordinate is equal to the supplied input
                isFinalPoint &= vecMovePos.epsilonEquals(vecOut, 4.1f);

                //if the path is invalid, stop trying and record the end as unreachable
                if(unit.team.isAI() && (noFound[0] || unit.isPathImpassable(World.toTile(vecMovePos.x), World.toTile(vecMovePos.y)) )){
                    if(attackTarget instanceof Building build){
                        unreachableBuildings.addUnique(build.pos());
                    }
                    attackTarget = null;
                    finishPath();
                    return;
                }
            }else{
                vecOut.set(vecMovePos);
            }

            float engageRange = unit.type.range - 10f;

            if(move){
                if(unit.type.circleTarget && attackTarget != null){
                    target = attackTarget;
                    circleAttack(80f);
                }else{
                    moveTo(vecOut,
                    attackTarget != null && unit.within(attackTarget, engageRange) && stance != UnitStance.ram ? engageRange :
                    unit.isGrounded() ? 0f :
                    attackTarget != null && stance != UnitStance.ram ? engageRange :
                    0f, unit.isFlying() ? 40f : 100f, false, null, isFinalPoint);
                }
            }

            //if stopAtTarget is set, stop trying to move to the target once it is reached - used for defending
            if(attackTarget != null && stopAtTarget && unit.within(attackTarget, engageRange - 1f)){
                attackTarget = null;
            }

            if(unit.isFlying()){
                unit.lookAt(vecMovePos);
            }else{
                faceTarget();
            }

            //reached destination, end pathfinding
            if(attackTarget == null && unit.within(vecMovePos, Math.max(5f, unit.hitSize / 2f))){
                finishPath();
            }

            if(stopWhenInRange && targetPos != null && unit.within(vecMovePos, engageRange * 0.9f)){
                finishPath();
                stopWhenInRange = false;
            }

        }else if(target != null){
            faceTarget();
        }
    }

    void finishPath(){
        Vec2 prev = targetPos;
        targetPos = null;

        if(commandQueue.size > 0){
            var next = commandQueue.remove(0);
            if(next instanceof Teamc target){
                commandTarget(target, this.stopAtTarget);
            }else if(next instanceof Vec2 position){
                commandPosition(position);
            }

            if(prev != null && stance == UnitStance.patrol){
                commandQueue.add(prev.cpy());
            }
        }else{
            if(group != null){
                group = null;
            }
        }
    }

    public void commandQueue(Position location){
        if(targetPos == null && attackTarget == null){
            if(location instanceof Teamc target){
                commandTarget(target, this.stopAtTarget);
            }else if(location instanceof Vec2 position){
                commandPosition(position);
            }
        }else if(commandQueue.size < maxCommandQueueSize && !commandQueue.contains(location)){
            commandQueue.add(location);
        }
    }

    @Override
    public float prefSpeed(){
        return group == null ? super.prefSpeed() : Math.min(group.minSpeed, unit.speed());
    }

    @Override
    public boolean shouldFire(){
        return stance != UnitStance.holdFire;
    }

    @Override
    public void hit(Bullet bullet){
        if(unit.team.isAI() && bullet.owner instanceof Teamc teamc && teamc.team() != unit.team && attackTarget == null &&
            //can only counter-attack every few seconds to prevent rapidly changing targets
            !(teamc instanceof Unit u && !u.checkTarget(unit.type.targetAir, unit.type.targetGround)) && timer.get(timerTarget4, 60f * 10f)){
            commandTarget(teamc, true);
        }
    }

    @Override
    public boolean keepState(){
        return true;
    }

    @Override
    public Teamc findTarget(float x, float y, float range, boolean air, boolean ground){
        return !nearAttackTarget(x, y, range) ? super.findTarget(x, y, range, air, ground) : attackTarget;
    }

    public boolean nearAttackTarget(float x, float y, float range){
        return attackTarget != null && attackTarget.within(x, y, range + 3f + (attackTarget instanceof Sized s ? s.hitSize()/2f : 0f));
    }

    @Override
    public boolean retarget(){
        //retarget faster when there is an explicit target
        return attackTarget != null ? timer.get(timerTarget, 10) : timer.get(timerTarget, 20);
    }

    public boolean hasCommand(){
        return targetPos != null;
    }

    public void setupLastPos(){
        lastTargetPos = targetPos;
    }

    @Override
    public void commandPosition(Vec2 pos){
        commandPosition(pos, false);
        if(commandController != null){
            commandController.commandPosition(pos);
        }
    }

    public void commandPosition(Vec2 pos, boolean stopWhenInRange){
        targetPos = pos;
        lastTargetPos = pos;
        attackTarget = null;
        pathId = Vars.controlPath.nextTargetId();
        this.stopWhenInRange = stopWhenInRange;
    }

    @Override
    public void commandTarget(Teamc moveTo){
        commandTarget(moveTo, false);
        if(commandController != null){
            commandController.commandTarget(moveTo);
        }
    }

    public void commandTarget(Teamc moveTo, boolean stopAtTarget){
        attackTarget = moveTo;
        this.stopAtTarget = stopAtTarget;
        pathId = Vars.controlPath.nextTargetId();
    }

    /*

    //TODO ひどい
    (does not work)

    public static float cohesionScl = 0.3f;
    public static float cohesionRad = 3f, separationRad = 1.1f, separationScl = 1f, flockMult = 0.5f;

    Vec2 calculateFlock(){
        if(local.isEmpty()) return flockVec.setZero();

        flockVec.setZero();
        separation.setZero();
        cohesion.setZero();
        massCenter.set(unit);

        float rad = unit.hitSize;
        float sepDst = rad * separationRad, cohDst = rad * cohesionRad;

        //"cohesed" isn't even a word smh
        int separated = 0, cohesed = 1;

        for(var other : local){
            float dst = other.dst(unit);
            if(dst < sepDst){
                separation.add(Tmp.v1.set(unit).sub(other).scl(1f / sepDst));
                separated ++;
            }

            if(dst < cohDst){
                massCenter.add(other);
                cohesed ++;
            }
        }

        if(separated > 0){
            separation.scl(1f / separated);
            flockVec.add(separation.scl(separationScl));
        }

        if(cohesed > 1){
            massCenter.scl(1f / cohesed);
            flockVec.add(Tmp.v1.set(massCenter).sub(unit).limit(cohesionScl * unit.type.speed));
            //seek mass center?
        }

        return flockVec;
    }*/
}
