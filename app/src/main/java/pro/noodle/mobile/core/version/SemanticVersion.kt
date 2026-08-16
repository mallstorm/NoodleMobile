package pro.noodle.mobile.core.version

data class SemanticVersion(val major: Int, val minor: Int, val patch: Int, val preRelease: String = "", val original: String = "") : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        val left = preRelease.split('.')
        val right = other.preRelease.split('.')
        for (i in 0 until minOf(left.size, right.size)) {
            if (left[i] == right[i]) continue
            val li = left[i].toIntOrNull(); val ri = right[i].toIntOrNull()
            if (li != null && ri != null) return li.compareTo(ri)
            if (li != null) return -1
            if (ri != null) return 1
            return left[i].compareTo(right[i], ignoreCase = true)
        }
        return left.size.compareTo(right.size)
    }

    override fun toString(): String = "$major.$minor.$patch" + if (preRelease.isNotEmpty()) "-$preRelease" else ""

    companion object {
        private val regex = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
        fun parseOrNull(value: String): SemanticVersion? {
            val raw = value.trim(); val m = regex.matchEntire(raw) ?: return null
            return SemanticVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4], raw)
        }
    }
}
