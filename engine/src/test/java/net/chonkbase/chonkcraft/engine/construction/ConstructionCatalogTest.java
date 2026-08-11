package net.chonkbase.chonkcraft.engine.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConstructionCatalogTest {

    @Test
    void completeNativeCatalogCarriesStageSemantics() {
        ConstructionCatalog catalog = ConstructionCatalog.generated("summer");
        assertEquals(12, catalog.constructions().size());

        ConstructionCatalog.Construction land = catalog.get("construction-land");
        assertNotNull(land);
        assertEquals("neutral/buildings/land_construction_site", land.sprite());
        assertEquals(0, land.stageAt(0.00).percent());
        assertEquals(0, land.stageAt(0.24).percent());
        assertEquals(25, land.stageAt(0.25).percent());
        assertEquals(25, land.stageAt(0.49).percent());
        assertEquals(50, land.stageAt(0.50).percent());
        assertEquals(50, land.stageAt(1.00).percent());
        assertEquals(ConstructionCatalog.Source.MAIN, land.stageAt(0.90).source());
        assertEquals(ConstructionCatalog.Source.CONSTRUCTION, land.stageAt(0.10).source());
        assertNotNull(land.stageAt(-1.0));
        assertNotNull(land.stageAt(5.0));
    }

    @Test
    void tilesetSpecificArtIsNotCollapsedToSummer() {
        assertEquals("tilesets/winter/neutral/buildings/land_construction_site",
                ConstructionCatalog.generated("winter").get("construction-land").sprite());
        assertEquals("tilesets/swamp/human/buildings/oil_platform_construction_site",
                ConstructionCatalog.generated("swamp")
                        .get("construction-human-oil-well").sprite());
        assertEquals("tilesets/summer/neutral/buildings/wall_construction_site",
                ConstructionCatalog.generated("swamp").get("construction-wall").sprite());
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionCatalog.generated("invented"));
    }
}
