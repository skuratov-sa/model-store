package com.model_store.modern.catalog.category.domain

data class Category(val id: Long, val name: String?, val parentId: Long?, val displayOrder: Int?)

data class CategoryBranch(val id: Long, val name: String?, val children: List<CategoryBranch>)

open class CategoryRuleViolation(message: String) : IllegalArgumentException(message)
class CategoryInvalidReference(message: String) : CategoryRuleViolation(message)
class CategoryIntegrityFailure(message: String) : IllegalStateException(message)

/** The persisted parent graph must be a forest, including when old rows are read. */
object CategoryTree {
    fun assemble(categories: List<Category>): List<CategoryBranch> {
        val byId = categories.associateBy { it.id }
        if (byId.size != categories.size) throw CategoryIntegrityFailure("Duplicate category ID")
        val ordered = categories.sortedWith(compareBy<Category>({ it.displayOrder == null }, { it.displayOrder }, { it.id }))
        val children = ordered.groupBy { it.parentId }
        val visiting = mutableSetOf<Long>()
        val visited = mutableSetOf<Long>()

        fun branch(category: Category): CategoryBranch {
            if (!visiting.add(category.id)) throw CategoryIntegrityFailure("Category cycle at ${category.id}")
            val result = CategoryBranch(category.id, category.name, children[category.id].orEmpty().map(::branch))
            visiting.remove(category.id)
            visited.add(category.id)
            return result
        }

        categories.forEach { category ->
            if (category.parentId != null && category.parentId !in byId) {
                throw CategoryIntegrityFailure("Missing parent ${category.parentId} for category ${category.id}")
            }
        }
        val roots = children[null].orEmpty().map(::branch)
        if (visited.size != categories.size) throw CategoryIntegrityFailure("Category graph contains a cycle")
        return roots
    }

    fun validateParent(parentId: Long?, knownIds: Set<Long>) {
        if (parentId != null && parentId !in knownIds) throw CategoryInvalidReference("Category parent does not exist: $parentId")
    }

    fun validateLinks(ids: List<Long>, knownIds: Set<Long>) {
        val missing = ids.firstOrNull { it !in knownIds }
        if (missing != null) throw CategoryInvalidReference("Category does not exist: $missing")
    }
}
