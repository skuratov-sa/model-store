package com.model_store.bridge.category

import com.model_store.model.dto.CategoryDto
import com.model_store.model.dto.CategoryResponse
import com.model_store.modern.catalog.category.application.CategoryUseCases
import com.model_store.modern.catalog.category.domain.CategoryBranch
import com.model_store.modern.shared.config.MigrationScenarioProperties
import com.model_store.service.CategoryService
import com.model_store.service.impl.CategoryServiceIml
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/** Opt-in adapter. All JPA calls are deferred off the WebFlux event loop. */
@Service
@Primary
@Profile("!modern")
class CategoryServiceBridge(
    private val legacy: CategoryServiceIml,
    private val modern: CategoryUseCases,
    private val scenarios: MigrationScenarioProperties,
) : CategoryService {
    private fun enabled() = scenarios.implementationFor("category") == MigrationScenarioProperties.Implementation.MODERN

    private fun <T : Any> blocking(call: () -> T): Mono<T> = Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic())

    override fun getCategories(): Mono<List<CategoryResponse>> =
        if (enabled()) blocking { modern.tree().map(::legacyResponse) } else legacy.getCategories()

    override fun createCategory(name: String, parentId: Long?): Mono<Long> =
        if (enabled()) blocking { modern.create(name, parentId) } else legacy.createCategory(name, parentId)

    override fun findByProductId(productId: Long): Flux<CategoryDto> =
        if (enabled()) blocking {
            modern.byProduct(productId).map { category ->
                // Lombok's builder is generated after Kotlin compilation in this mixed module.
                dtoConstructor.newInstance(category.id, category.name) as CategoryDto
            }
        }.flatMapMany { Flux.fromIterable(it) } else legacy.findByProductId(productId)

    override fun updateCategory(categoryId: Long, name: String): Mono<Void> =
        if (enabled()) blocking { modern.rename(categoryId, name); true }.then() else legacy.updateCategory(categoryId, name)

    override fun addLinkProductAndCategories(categoryIds: List<Long>, productId: Long): Mono<Void> =
        if (enabled()) blocking { modern.addLinks(productId, categoryIds); true }.then()
        else legacy.addLinkProductAndCategories(categoryIds, productId)

    private fun legacyResponse(branch: CategoryBranch): CategoryResponse =
        CategoryResponse(branch.id, branch.name).apply {
            branch.children.map(::legacyResponse).forEach(::addCategoryResponse)
        }

    private companion object {
        val dtoConstructor by lazy {
            CategoryDto::class.java.getDeclaredConstructor(Long::class.javaObjectType, String::class.java)
                .apply { isAccessible = true }
        }
    }
}
