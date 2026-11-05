package com.cuentivo.app.domain

import com.cuentivo.app.data.local.ProductDao
import com.cuentivo.app.data.local.ProductEntity

class ProductRepository(private val productDao: ProductDao) {
    suspend fun create(product: ProductEntity) {
        validate(product)
        productDao.insert(product)
    }

    suspend fun update(product: ProductEntity) {
        validate(product)
        productDao.update(product)
    }

    suspend fun archive(productId: String, archivedAtEpochMillis: Long) {
        require(productId.isNotBlank())
        productDao.archive(productId, archivedAtEpochMillis)
    }

    suspend fun search(businessId: String, query: String): List<ProductEntity> {
        require(businessId.isNotBlank())
        return productDao.searchActive(businessId, query.trim(), query.trim())
    }

    private fun validate(product: ProductEntity) {
        require(product.businessId.isNotBlank())
        require(product.name.isNotBlank())
        require(product.salePriceCents >= 0)
        // A locales: el smart cast no cruza el borde de módulo (ProductEntity vive en :shared).
        val costPriceCents = product.costPriceCents
        val stock = product.stock
        require(costPriceCents == null || costPriceCents >= 0)
        require(stock == null || stock >= 0)
    }
}
