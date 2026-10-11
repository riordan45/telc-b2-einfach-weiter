package com.example.weiterb2

/** Each menu owns a path of child screens; Back removes one level at a time. */
internal data class AppNavigation(
    val menu: String = HOME,
    val path: List<String> = emptyList(),
) {
    val canGoBack: Boolean get() = path.isNotEmpty() || menu != HOME

    fun selectMenu(menu: String) = AppNavigation(menu)
    fun open(screen: String) = copy(path = path + screen)
    fun back(): AppNavigation = when {
        path.isNotEmpty() -> copy(path = path.dropLast(1))
        menu != HOME -> AppNavigation()
        else -> this
    }

    companion object { const val HOME = "library" }
}
