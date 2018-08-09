package fr.smarquis.appstore

import org.junit.Assert.fail
import org.junit.Test
import java.lang.NullPointerException

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
        assert(version.major == MAJOR)
        assert(version.minor == MINOR)
        assert(version.patch == PATCH)
        assert(version.label == LABEL)
    }

    @Test
    fun parseInvalid() {
        try {
            SemVer.parse(null)
            fail()
        } catch (e: NullPointerException) {
        }
        try {
            SemVer.parse("")
            fail()
        } catch (e: Exception) {
        }
        try {
            SemVer.parse(".1")
            fail()
        } catch (e: Exception) {
        }
        try {
            SemVer.parse("1.")
            fail()
        } catch (e: Exception) {
        }
        try {
            SemVer.parse("1.a")
            fail()
        } catch (e: Exception) {
        }
        try {
            SemVer.parse("1.1.1.1")
            fail()
        } catch (e: Exception) {
        }
        try {
            SemVer.parse("0-")
            fail()
        } catch (e: Exception) {
        }
    }


    @Test
    fun compareTo() {
        assert(VERSION_1_2_3_SNAPSHOT.compareTo(VERSION_1_2_3_SNAPSHOT) == 0)
        assert(SemVer(1, 2, 3) < SemVer(3, 2, 1))
        assert(VERSION_1_2_3_SNAPSHOT.compareTo(VERSION_1_2_3_SNAPSHOT) == 0)
        assert(SemVer(1) > SemVer(1, label = "alpha"))
    }
}