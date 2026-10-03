package com.model_store.modern.catalog.category.infrastructure

import com.model_store.modern.catalog.category.application.CategoryPort
import com.model_store.modern.catalog.category.domain.Category
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.EntityManager
import jakarta.persistence.Table
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Entity
@Table(name = "category")
class CategoryRow(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "name") var name: String? = null,
    @Column(name = "parent_id") var parentId: Long? = null,
    @Column(name = "display_order") var displayOrder: Int? = null,
) {
    fun domain() = Category(requireNotNull(id), name, parentId, displayOrder)
}

@Entity
@Table(name = "product_category")
class ProductCategoryRow(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "product_id", nullable = false) var productId: Long = 0,
    @Column(name = "category_id", nullable = false) var categoryId: Long = 0,
)

@Repository
class JpaCategoryPort(
    private val entityManager: EntityManager,
    private val jdbc: JdbcTemplate,
) : CategoryPort {
    override fun all(): List<Category> = entityManager.createQuery("select c from CategoryRow c", CategoryRow::class.java)
        .resultList.map(CategoryRow::domain)
    override fun existingIds(ids: Collection<Long>): Set<Long> =
        if (ids.isEmpty()) emptySet() else entityManager.createQuery(
            "select c.id from CategoryRow c where c.id in :ids", Long::class.java,
        ).setParameter("ids", ids).resultList.toSet()

    override fun create(name: String, parentId: Long?): Long {
        val row = CategoryRow(name = name, parentId = parentId)
        entityManager.persist(row)
        return requireNotNull(row.id)
    }

    override fun rename(id: Long, name: String) {
        val row = entityManager.find(CategoryRow::class.java, id) ?: return
        row.name = name
    }

    override fun findByProduct(productId: Long): List<Category> = entityManager.createQuery(
        "select c from ProductCategoryRow pc, CategoryRow c where pc.categoryId = c.id and pc.productId = :productId order by pc.id",
        CategoryRow::class.java,
    ).setParameter("productId", productId).resultList.map(CategoryRow::domain)
    override fun productExists(productId: Long): Boolean =
        jdbc.queryForObject("select exists(select 1 from product where id = ?)", Boolean::class.java, productId) == true

    override fun addLinks(productId: Long, categoryIds: List<Long>) {
        categoryIds.forEach { entityManager.persist(ProductCategoryRow(productId = productId, categoryId = it)) }
    }
}
