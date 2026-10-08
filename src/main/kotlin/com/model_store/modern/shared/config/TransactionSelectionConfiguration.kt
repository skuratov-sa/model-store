package com.model_store.modern.shared.config

import org.reactivestreams.Publisher
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.transaction.interceptor.DefaultTransactionAttribute
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute
import org.springframework.transaction.interceptor.TransactionAttribute
import org.springframework.transaction.interceptor.TransactionAttributeSource
import java.lang.reflect.Method

/** Routes unqualified legacy publishers and synchronous modern commands to their own transaction managers. */
@Configuration(proxyBeanMethods = false)
class TransactionSelectionConfiguration {
    @Bean
    @Primary
    fun selectedTransactionAttributeSource(
        @Qualifier("transactionAttributeSource") source: TransactionAttributeSource,
    ): TransactionAttributeSource = object : TransactionAttributeSource {
        override fun isCandidateClass(targetClass: Class<*>): Boolean = source.isCandidateClass(targetClass)

        override fun hasTransactionAttribute(method: Method, targetClass: Class<*>?): Boolean =
            source.hasTransactionAttribute(method, targetClass)

        override fun getTransactionAttribute(method: Method, targetClass: Class<*>?): TransactionAttribute? {
            val attribute = source.getTransactionAttribute(method, targetClass) ?: return null
            if (!attribute.qualifier.isNullOrBlank()) return attribute

            // Spring's transaction interceptor resolves the manager before it sees the return value.
            // Keep annotation settings, including rollback rules, when adding the missing qualifier.
            val selected = when (attribute) {
                is RuleBasedTransactionAttribute -> RuleBasedTransactionAttribute(attribute)
                else -> DefaultTransactionAttribute(attribute)
            }
            // Match the concrete method Spring used to find @Transactional, including inherited or interface declarations.
            val targetMethod = targetClass?.let { AopUtils.getMostSpecificMethod(method, it) } ?: method
            selected.qualifier = if (Publisher::class.java.isAssignableFrom(targetMethod.returnType))
                "connectionFactoryTransactionManager" else "transactionManager"
            return selected
        }
    }
}
