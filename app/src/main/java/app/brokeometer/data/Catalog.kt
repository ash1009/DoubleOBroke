package app.brokeometer.data

/** Categories and sub-categories together, with the lookups screens need. */
data class Catalog(
    val categories: List<CategoryEntity> = emptyList(),
    val subcategories: List<SubcategoryEntity> = emptyList(),
) {
    val subsByCategory: Map<Long, List<SubcategoryEntity>> = subcategories.groupBy { it.categoryId }
    private val subById: Map<Long, SubcategoryEntity> = subcategories.associateBy { it.id }
    private val categoryById: Map<Long, CategoryEntity> = categories.associateBy { it.id }

    val isEmpty: Boolean get() = categories.isEmpty()

    fun sub(id: Long?): SubcategoryEntity? = id?.let { subById[it] }

    fun category(id: Long?): CategoryEntity? = id?.let { categoryById[it] }

    fun categoryOfSub(subId: Long?): CategoryEntity? = sub(subId)?.let { categoryById[it.categoryId] }

    fun subsOf(categoryId: Long?): List<SubcategoryEntity> = categoryId?.let { subsByCategory[it] } ?: emptyList()

    /** "Lunch, Food", the way an entry is described in an alert. */
    fun label(subId: Long?): String? {
        val sub = sub(subId) ?: return null
        val category = categoryById[sub.categoryId] ?: return sub.name
        return "${sub.name}, ${category.name}"
    }
}
