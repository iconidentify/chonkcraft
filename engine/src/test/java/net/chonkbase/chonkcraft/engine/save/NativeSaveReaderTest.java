package net.chonkbase.chonkcraft.engine.save;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class NativeSaveReaderTest {

    @Test
    void readsTheCompleteDataGrammarAndIgnoresUnknownCalls() {
        NativeSaveReader reader = new NativeSaveReader();
        Object[][] captured = new Object[1][];
        reader.register("Capture", args -> {
            captured[0] = args;
            return new Object[] {42.0};
        });

        reader.run("""
                -- comments and the legacy map-load guard are harmless
                local oldCreateUnit = CreateUnit
                function CreateUnit() end
                UnknownFromANewerVersion({future = true})
                result = Capture("line\\nquote\\\"slash\\\\", -12.5, true, nil,
                    {7, 8, named = "value", nested = {false, 9}})
                GameCycle = 123
                """);

        assertEquals(42.0, reader.globals().rawGet("result"));
        assertEquals(123.0, reader.globals().rawGet("GameCycle"));
        assertEquals("line\nquote\"slash\\", captured[0][0]);
        assertEquals(-12.5, captured[0][1]);
        assertEquals(true, captured[0][2]);
        assertEquals(null, captured[0][3]);
        SaveTable table = (SaveTable) captured[0][4];
        assertEquals(List.of(7.0, 8.0), table.array());
        assertEquals("value", table.rawGet("named"));
        assertEquals(List.of(false, 9.0),
                ((SaveTable) table.rawGet("nested")).array());
    }
}

