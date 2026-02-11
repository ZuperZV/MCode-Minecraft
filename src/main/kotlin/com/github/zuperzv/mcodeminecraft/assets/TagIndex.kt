package com.github.zuperzv.mcodeminecraft.assets

data class TagIndex(
    val itemTags: Map<String, Set<String>>,
    val blockTags: Map<String, Set<String>>,
    val fluidTags: Map<String, Set<String>>
) {
    private val locale = java.util.Locale.US

    fun hasTag(tagId: String): Boolean {
        val normalized = tagId.trim().removePrefix("#").lowercase(locale)
        return itemTags.containsKey(normalized) || blockTags.containsKey(normalized)
    }

    fun allTags(): Set<String> {
        return (itemTags.keys + blockTags.keys).toSet()
    }

    fun allFluidIds(): Set<String> {
        val resolved = LinkedHashSet<String>()
        val visiting = HashSet<String>()

        fun visit(tagId: String) {
            if (!visiting.add(tagId)) return
            val values = fluidTags[tagId] ?: return
            values.forEach { value ->
                val trimmed = value.trim()
                if (trimmed.startsWith("#")) {
                    visit(trimmed.removePrefix("#").lowercase(locale))
                } else if (trimmed.isNotEmpty()) {
                    resolved.add(trimmed.lowercase(locale))
                }
            }
        }

        fluidTags.keys.forEach { visit(it) }
        return resolved
    }
}
