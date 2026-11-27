package com.ledgerflow.app.domain

import com.ledgerflow.app.data.local.CategoryEntity
import java.util.Locale

data class CategoryTreeNode(
    val category: CategoryEntity,
    val depth: Int,
    val children: List<CategoryTreeNode>,
) {
    val totalDescendantCount: Int
        get() = children.sumOf { 1 + it.totalDescendantCount }
}

data class FlatCategoryEntry(
    val category: CategoryEntity,
    val depth: Int,
    val breadcrumb: String,
    val childCount: Int,
)

/**
 * Arbitrary-depth recursive category tree engine with cycle detection, sibling duplicate guards,
 * multi-level search, and descendant traversal.
 */
object CategoryTree {

    fun buildTree(
        categories: List<CategoryEntity>,
        kindFilter: String? = null,
    ): List<CategoryTreeNode> {
        val active = categories.filter {
            it.deletedAtEpochMs == null &&
                (kindFilter == null || it.kind.equals(kindFilter, ignoreCase = true))
        }
        val activeIds = active.map { it.id }.toHashSet()
        val childrenMap = active.groupBy { cat ->
            val pid = cat.parentCategoryId
            if (pid != null && pid != cat.id && pid in activeIds) pid else "__ROOT__"
        }

        val visited = HashSet<String>(active.size)

        fun buildNode(cat: CategoryEntity, depth: Int): CategoryTreeNode {
            if (!visited.add(cat.id)) {
                return CategoryTreeNode(category = cat, depth = depth, children = emptyList())
            }
            val directKids = childrenMap[cat.id].orEmpty()
                .sortedBy { it.name.lowercase(Locale.US) }
                .map { child -> buildNode(child, depth + 1) }
            return CategoryTreeNode(category = cat, depth = depth, children = directKids)
        }

        val roots = childrenMap["__ROOT__"].orEmpty()
            .sortedWith(
                compareBy<CategoryEntity> { it.name.equals("General", ignoreCase = true) || it.name.equals("Other", ignoreCase = true) }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
            .map { root -> buildNode(root, 0) }

        // Recover any disconnected cyclic component safely as roots rather than silently dropping user data
        val orphans = active
            .filter { it.id !in visited }
            .sortedBy { it.name.lowercase(Locale.US) }
            .map { orphan -> buildNode(orphan, 0) }

        return roots + orphans
    }

    /**
     * Searches across all hierarchy levels. When a nested node matches, its ancestor chain is preserved
     * so users immediately see where it sits in the tree. When a parent matches directly, its children stay visible.
     */
    fun filterTree(
        nodes: List<CategoryTreeNode>,
        rawQuery: String,
    ): List<CategoryTreeNode> {
        val q = rawQuery.trim()
        if (q.isBlank()) return nodes

        fun filterNode(node: CategoryTreeNode): CategoryTreeNode? {
            val selfMatches = node.category.name.contains(q, ignoreCase = true) ||
                node.category.description.contains(q, ignoreCase = true)
            if (selfMatches) return node
            val matchingKids = node.children.mapNotNull(::filterNode)
            return if (matchingKids.isNotEmpty()) {
                node.copy(children = matchingKids)
            } else {
                null
            }
        }

        return nodes.mapNotNull(::filterNode)
    }

    fun flattenTree(
        nodes: List<CategoryTreeNode>,
        catMap: Map<String, CategoryEntity>,
    ): List<FlatCategoryEntry> {
        val out = mutableListOf<FlatCategoryEntry>()
        fun visit(node: CategoryTreeNode) {
            out += FlatCategoryEntry(
                category = node.category,
                depth = node.depth,
                breadcrumb = breadcrumbPath(node.category.id, catMap),
                childCount = node.totalDescendantCount,
            )
            node.children.forEach(::visit)
        }
        nodes.forEach(::visit)
        return out
    }

    fun ancestorsOf(
        catId: String?,
        catMap: Map<String, CategoryEntity>,
    ): List<CategoryEntity> {
        if (catId.isNullOrBlank()) return emptyList()
        val chain = mutableListOf<CategoryEntity>()
        val visited = HashSet<String>()
        var cursorId: String? = catId
        while (!cursorId.isNullOrBlank() && visited.add(cursorId)) {
            val node = catMap[cursorId] ?: break
            chain += node
            cursorId = node.parentCategoryId
        }
        return chain.asReversed()
    }

    fun depthOf(
        catId: String?,
        catMap: Map<String, CategoryEntity>,
    ): Int = (ancestorsOf(catId, catMap).size - 1).coerceAtLeast(0)

    fun rootOf(
        catId: String?,
        catMap: Map<String, CategoryEntity>,
    ): CategoryEntity? = ancestorsOf(catId, catMap).firstOrNull()

    fun breadcrumbPath(
        catId: String?,
        catMap: Map<String, CategoryEntity>,
        separator: String = " › ",
    ): String {
        val chain = ancestorsOf(catId, catMap)
        return chain.joinToString(separator) { it.name }
    }

    fun isDescendantOrSelf(
        candidateId: String?,
        targetRootId: String?,
        catMap: Map<String, CategoryEntity>,
    ): Boolean {
        if (candidateId.isNullOrBlank() || targetRootId.isNullOrBlank()) return false
        return ancestorsOf(candidateId, catMap).any { it.id == targetRootId }
    }

    fun collectDescendantIds(
        rootId: String,
        categories: List<CategoryEntity>,
        includeSelf: Boolean = false,
    ): Set<String> {
        val byParent = categories
            .filter { it.deletedAtEpochMs == null && it.parentCategoryId != null }
            .groupBy { it.parentCategoryId!! }
        val result = LinkedHashSet<String>()
        if (includeSelf) result.add(rootId)
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        val visited = HashSet<String>().apply { add(rootId) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (child in byParent[cur].orEmpty()) {
                if (visited.add(child.id)) {
                    result.add(child.id)
                    queue.add(child.id)
                }
            }
        }
        return result
    }

    fun wouldCreateCycle(
        categoryId: String,
        proposedParentId: String?,
        categories: List<CategoryEntity>,
    ): Boolean {
        if (proposedParentId.isNullOrBlank()) return false
        if (proposedParentId == categoryId) return true
        val subtree = collectDescendantIds(categoryId, categories, includeSelf = true)
        return proposedParentId in subtree
    }

    fun hasDuplicateSibling(
        name: String,
        kind: String,
        parentId: String?,
        excludeCategoryId: String?,
        categories: List<CategoryEntity>,
    ): Boolean {
        val cleanName = name.trim()
        val normKind = if (kind.trim().equals("INCOME", ignoreCase = true)) "INCOME" else "EXPENSE"
        if (cleanName.isBlank()) return false
        return categories.any { cat ->
            cat.deletedAtEpochMs == null &&
                cat.id != excludeCategoryId &&
                cat.kind.equals(normKind, ignoreCase = true) &&
                cat.parentCategoryId == parentId &&
                cat.name.trim().equals(cleanName, ignoreCase = true)
        }
    }
}
