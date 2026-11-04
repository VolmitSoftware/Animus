package art.arcane.animus.bukkit.folia;

import art.arcane.animus.bukkit.navigation.BlockCells;
import art.arcane.animus.core.navigation.*;
import art.arcane.animus.core.virtual.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Preview geometry from owned, sent voxels and their collision shapes. No rayTraceBlocks or unowned live reads. */
final class StationaryTerrain {
    private final FoliaRegions owner;
    StationaryTerrain(FoliaRegions owner) { this.owner = owner; }
    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        long cells() { return (long) (maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1); }
    }
    static Bounds body(Vec3 feet, double width, double height) {
        return new Bounds(floor(feet.x()-width/2+1e-6)-1, floor(feet.y()+1e-6)-1, floor(feet.z()-width/2+1e-6)-1,
                floor(feet.x()+width/2-1e-6)+1, floor(feet.y()+height-1e-6)+1, floor(feet.z()+width/2-1e-6)+1);
    }
    static Bounds ray(Vec3 start, Vec3 end) {
        return new Bounds(floor(Math.min(start.x(),end.x()))-1,floor(Math.min(start.y(),end.y()))-1,floor(Math.min(start.z(),end.z()))-1,
                floor(Math.max(start.x(),end.x()))+1,floor(Math.max(start.y(),end.y()))+1,floor(Math.max(start.z(),end.z()))+1);
    }
    boolean admitted(Player viewer, World world, Bounds bounds, int maximumCells) {
        owner.require(viewer);
        if (bounds.cells()>maximumCells) return false;
        if (!owner.owns(world,bounds.minX()>>4,bounds.minZ()>>4,bounds.maxX()>>4,bounds.maxZ()>>4)) return false;
        if (bounds.minY()<world.getMinHeight() || bounds.maxY()>=world.getMaxHeight()) return false;
        for(int x=bounds.minX()>>4;x<=bounds.maxX()>>4;x++) for(int z=bounds.minZ()>>4;z<=bounds.maxZ()>>4;z++)
            if (!world.isChunkLoaded(x,z) || !owner.sent(viewer,x,z)) return false;
        return true;
    }
    boolean supported(Player viewer, World world, Vec3 feet, double width, double height) {
        Bounds bounds=body(feet,width,height);
        if (!admitted(viewer,world,bounds,128)) return false;
        return MotionValidator.check(feet,feet,width,height,MotionValidator.Medium.GROUND,true,128,
                (x,y,z)->sample(viewer,world,x,y,z)).clear();
    }
    boolean lineOfSight(Player viewer, World world, Vec3 start, Vec3 end, double distance) {
        if (!Double.isFinite(distance) || distance<0 || distance>4.5) return false;
        Bounds bounds=ray(start,end);
        if (!admitted(viewer,world,bounds,512)) return false;
        Vec3 direction=distance<1e-7?new Vec3(0,0,1):end.subtract(start).normalized();
        for(int x=bounds.minX();x<=bounds.maxX();x++) for(int z=bounds.minZ();z<=bounds.maxZ();z++) for(int y=bounds.minY();y<=bounds.maxY();y++) {
            VoxelSnapshot.Cell cell=sample(viewer,world,x,y,z);
            if (cell.unavailable()) return false;
            for (VoxelSnapshot.Cell.Box box : cell.boxes())
                if (new Hitbox(new Vec3(x+box.minX(),y+box.minY(),z+box.minZ()),new Vec3(x+box.maxX(),y+box.maxY(),z+box.maxZ()))
                        .rayDistance(start,direction,Math.max(0,distance-1e-5)).isPresent()) return false;
        }
        return true;
    }
    private VoxelSnapshot.Cell sample(Player viewer, World world, int x, int y, int z) {
        // Recheck before every live sample, in addition to the whole-footprint admission.
        if (!owner.owns(viewer) || !owner.owns(world,x>>4,z>>4,x>>4,z>>4)
                || !world.isChunkLoaded(x>>4,z>>4) || !owner.sent(viewer,x>>4,z>>4)) return VoxelSnapshot.Cell.UNAVAILABLE;
        return BlockCells.of(world.getBlockAt(x,y,z));
    }
    private static int floor(double value) {
        if (!Double.isFinite(value) || Math.abs(value)>30_000_000) throw new IllegalArgumentException("Coordinate outside bounds");
        return (int)Math.floor(value);
    }
}
