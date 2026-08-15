package net.chonkbase.chonkcraft.desktop;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;

/** Headless throughput and combat-liveness check for the massive battle. */
public final class BattleShowcaseBenchmark {

    private BattleShowcaseBenchmark() {
    }

    public static void main(String[] args) {
        AssetSource assets = AssetSource.fromEnvironment();
        if (assets == null) {
            throw new IllegalStateException(
                    "Set CHONKCRAFT_ASSET_PACK or WC2_INSTALL_DIR");
        }
        int units = number(args, 0, 400, BattleShowcase.MIN_UNITS, BattleShowcase.MAX_UNITS);
        int cycles = number(args, 1, 1_800, 30, 18_000);
        GameData data = new GameData(assets);
        String mapName = BattleShowcase.defaultMapName(assets);
        byte[] mapBytes = mapName == null ? null : assets.map(mapName);
        if (mapBytes == null) {
            throw new IllegalStateException("No showcase map is available");
        }
        PudMap source = PudReader.read(mapBytes);
        World world = new World(
                GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                Player.forSoloGame(source));
        data.configureWorld(world, source);
        BattleShowcase.Result result = BattleShowcase.deploy(
                world, data.unitTypes().types(), units);
        BattleShowcase.Director director = new BattleShowcase.Director(world, result);

        int initialAlive = alive(result);
        BattleShowcase.Status status = null;
        long started = System.nanoTime();
        for (int cycle = 0; cycle < cycles; cycle++) {
            world.tick();
            status = director.update();
            if (status.complete()) {
                break;
            }
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        int alive = alive(result);
        long damaged = result.units().stream()
                .filter(unit -> unit.isAlive()
                        && unit.hitPoints() < unit.type().hitPoints())
                .count();

        System.out.printf("Massive battle benchmark%n");
        System.out.printf("  Map: %s%n", mapName);
        System.out.printf("  Units: %d deployed (%d human, %d orc)%n",
                result.deployed(), result.humanUnits(), result.orcUnits());
        System.out.printf("  Simulated: %d cycles (%.1f game seconds) in %.3f real seconds%n",
                cycles, cycles / (double) World.CYCLES_PER_SECOND, seconds);
        System.out.printf("  Throughput: %.0f cycles/second (%.1fx real time)%n",
                cycles / seconds,
                cycles / seconds / World.CYCLES_PER_SECOND);
        System.out.printf("  Combat: %d casualties, %d damaged survivors%n",
                initialAlive - alive, damaged);
        if (status != null && status.complete()) {
            System.out.printf("  Outcome: %s%n", status.message());
        } else if (status != null) {
            System.out.printf("  Remaining: %d human, %d orc%n",
                    status.humanAlive(), status.orcAlive());
        }

        int minimum = Math.max(BattleShowcase.MIN_UNITS,
                (int) Math.floor(units * 0.75));
        if (result.deployed() < minimum) {
            throw new IllegalStateException("only " + result.deployed()
                    + " of " + units + " requested units fit");
        }
        if (initialAlive == alive && damaged == 0) {
            throw new IllegalStateException("the opposing armies never engaged");
        }
    }

    private static int alive(BattleShowcase.Result result) {
        return (int) result.units().stream().filter(unit -> unit.isAlive()).count();
    }

    private static int number(String[] args, int index, int fallback, int min, int max) {
        if (index >= args.length) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(args[index])));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
