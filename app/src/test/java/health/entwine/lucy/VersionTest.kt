// Q-14 update-check comparator.
package health.entwine.lucy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTest {
    @Test fun below() {
        assertTrue(versionBelow("0.1.0", "0.2.0"))
        assertTrue(versionBelow("0.1.0", "0.1.1"))
        assertTrue(versionBelow("0.9.9", "1.0.0"))
    }

    @Test fun notBelow() {
        assertFalse(versionBelow("0.1.0", "0.1.0"))
        assertFalse(versionBelow("1.0.0", "0.9.9"))
        assertFalse(versionBelow("0.1.1", "0.1.0"))
    }
}
