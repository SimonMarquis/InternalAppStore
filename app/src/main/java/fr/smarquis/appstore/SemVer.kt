package fr.smarquis.appstore

import java.lang.NullPointerException

/**
 * Inspired by https://github.com/swiftzer/semver
 */
data class SemVer(
        val major: Int = 0,
        val minor: Int = 0,
        val patch: Int = 0,
        val label: String? = null
) : Comparable<SemVer> {

    companion object {

        private val INVALID = SemVer(0, 0, 0, null)

        fun nonNull(semVer: SemVer?): SemVer = semVer ?: INVALID

        fun parse(version: String?): SemVer {
            if (version == null) {
                throw NullPointerException()
            }
            val pattern = Regex("""(\d+)(?:\.(\d+)(?:\.(\d+))?)?(?:-(.+))?""")
            val result = pattern.matchEntire(version)
                    ?: throw IllegalArgumentException("Invalid version string [$version]")
            return SemVer(
                    major = result.groupValues[1].toIntOr(0),
                    minor = result.groupValues[2].toIntOr(0),
                    patch = result.groupValues[3].toIntOr(0),
                    label = result.groupValues[4].let { if (it.isEmpty()) null else it }
            )
        }

        private fun String.toIntOr(fallback: Int): Int = try {
            toInt()
        } catch (e: NumberFormatException) {
            fallback
        }

    }

    init {
        require(major >= 0) { "Major version must be a positive number" }
        require(minor >= 0) { "Minor version must be a positive number" }
        require(patch >= 0) { "Patch version must be a positive number" }
        label?.let { require(it.matches(Regex(""".+"""))) { "Label is not valid" } }
    }

    override fun toString(): String = buildString {
        append("$major.$minor.$patch")
        label?.let { append("-$it") }
    }


    /**
     * @return a negative integer, zero, or a positive integer as this object is less than, equal to, or greater than the specified object.
     */
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (label == other.label) return 0
        if (label.isNullOrEmpty()) return 1 // 1.0.0 > 1.0.0-alpha
        if (other.label.isNullOrEmpty()) return -1  // 1.0.0-alpha < 1.0.0
        return (label.orEmpty()).compareTo(other.label.orEmpty(), ignoreCase = true)
    }

}

