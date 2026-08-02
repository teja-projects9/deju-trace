package org.deju.plugin.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Size formatting for the disclosure and clear dialogs. */
class DejuDataInventoryTest {

    @Test
    void readsAsAPersonWouldSayIt() {
        assertEquals("nothing", DejuDataInventory.humanSize(0));
        assertEquals("nothing", DejuDataInventory.humanSize(-1));
        assertEquals("512 B", DejuDataInventory.humanSize(512));
        assertEquals("1 KB", DejuDataInventory.humanSize(1024));
        assertEquals("105 KB", DejuDataInventory.humanSize(107_520));
        assertEquals("11.1 MB", DejuDataInventory.humanSize(11_589_413));
    }
}
