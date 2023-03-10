package fr.smarquis.appstore

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SemVerTest {

    companion object {

        private const val MAJOR = 1
        private const val MINOR = 2
        private const val PATCH = 3
        private const val LABEL = "snapshot"
        val VERSION_1_2_3_SNAPSHOT: SemVer = SemVer(major = MAJOR, minor = MINOR, patch = PATCH, label = LABEL)
    }

    @Test
    fun parse() {
        SemVer.parse("0")
        SemVer.parse("0.0.0")
        SemVer.parse("1")
        SemVer.parse("1.0")
        SemVer.parse("1.2.3")
        SemVer.parse("1.2.3-test")

        val version = SemVer.parse("$MAJOR.$MINOR.$PATCH-$LABEL")
        assertEquals(MAJOR, version.major)
        assertEquals(MINOR, version.minor)
        assertEquals(PATCH, version.patch)
        assertEquals(LABEL, version.label)
    }

    @Test
    fun parseInvalid() {
        assertFailsWith<NullPointerException> { SemVer.parse(null) }
        assertFails { SemVer.parse("") }
        assertFails { SemVer.parse(".1") }
        assertFails { SemVer.parse("1.") }
        assertFails { SemVer.parse("1.a") }
        assertFails { SemVer.parse("1.1.1.1") }
        assertFails { SemVer.parse("0-") }
    }


    @Test
    fun compareTo() {
        assertEquals(0, VERSION_1_2_3_SNAPSHOT.compareTo(VERSION_1_2_3_SNAPSHOT))
        assertTrue(SemVer(1, 2, 3) < SemVer(3, 2, 1))
        assertEquals(0, VERSION_1_2_3_SNAPSHOT.compareTo(VERSION_1_2_3_SNAPSHOT))
        assertTrue(SemVer(1) > SemVer(1, label = "alpha"))
    }
}
