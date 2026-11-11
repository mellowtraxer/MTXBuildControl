package de.ricci.buildcontrol

internal fun isProfileRepository(fullName: String): Boolean {
    val owner = fullName.substringBefore('/')
    val name = fullName.substringAfter('/', missingDelimiterValue = "")
    return name.equals(owner, ignoreCase = true) && name.isNotEmpty()
}
