package net.chonkbase.chonkcraft.engine.unit;

/**
 * How a unit type gathers one kind of resource.
 *
 * <p>Implements {@code ResourceInfo} from {@code src/include/unittype.h},
 * filled from a {@code CanGatherResources} entry:
 *
 * <pre>
 *   {"file-when-loaded", "human/units/peasant_with_gold.png",
 *    "resource-id", "gold", "resource-capacity", 100,
 *    "wait-at-resource", 150, "wait-at-depot", 150}
 * </pre>
 *
 * <p>The three kinds differ in where the resource is. Gold sits in a mine the
 * worker walks into; wood is terrain, chopped square by square; oil needs a
 * platform built over it first and a refinery to unload at. Those differences
 * are the {@code terrainHarvester} and {@code refineryHarvester} flags.
 */
public final class ResourceInfo {

    private final UnitType.Resource resource;
    private String fileWhenLoaded = "";
    private String fileWhenEmpty = "";
    private int capacity = 100;
    private int step = 1;

    /** Cycles spent at the resource per load. */
    private int waitAtResource;

    /** Cycles spent unloading at the depot. */
    private int waitAtDepot;

    /** Whether the resource is terrain, as wood is, rather than a building. */
    private boolean terrainHarvester;

    /** Whether it must be unloaded at a refinery rather than any depot. */
    private boolean refineryHarvester;

    /** Whether the worker stands outside rather than entering. */
    private boolean harvestFromOutside;

    public ResourceInfo(UnitType.Resource resource) {
        this.resource = resource;
    }

    public UnitType.Resource resource() {
        return resource;
    }

    /** The sprite to draw while carrying a load. */
    public String fileWhenLoaded() {
        return fileWhenLoaded;
    }

    public void setFileWhenLoaded(String fileWhenLoaded) {
        this.fileWhenLoaded = fileWhenLoaded;
    }

    /** The sprite to draw while empty, for types that differ. */
    public String fileWhenEmpty() {
        return fileWhenEmpty;
    }

    public void setFileWhenEmpty(String fileWhenEmpty) {
        this.fileWhenEmpty = fileWhenEmpty;
    }

    /** How much the worker carries per trip. */
    public int capacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    /** How much is taken from the source per harvesting cycle. */
    public int step() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public int waitAtResource() {
        return waitAtResource;
    }

    public void setWaitAtResource(int waitAtResource) {
        this.waitAtResource = waitAtResource;
    }

    public int waitAtDepot() {
        return waitAtDepot;
    }

    public void setWaitAtDepot(int waitAtDepot) {
        this.waitAtDepot = waitAtDepot;
    }

    public boolean terrainHarvester() {
        return terrainHarvester;
    }

    public void setTerrainHarvester(boolean terrainHarvester) {
        this.terrainHarvester = terrainHarvester;
    }

    public boolean refineryHarvester() {
        return refineryHarvester;
    }

    public void setRefineryHarvester(boolean refineryHarvester) {
        this.refineryHarvester = refineryHarvester;
    }

    public boolean harvestFromOutside() {
        return harvestFromOutside;
    }

    public void setHarvestFromOutside(boolean harvestFromOutside) {
        this.harvestFromOutside = harvestFromOutside;
    }

    @Override
    public String toString() {
        return resource + " x" + capacity;
    }
}
