package cz.preclikos.tvhstream.settings

enum class AppLanguage(val languageTag: String) {
    SYSTEM(""),
    GERMAN("de"),
    ENGLISH("en"),
    CZECH("cs");

    companion object {
        fun fromLanguageTags(languageTags: String): AppLanguage {
            val language = languageTags.substringBefore(',').substringBefore('-')
            return entries.firstOrNull {
                it.languageTag.isNotEmpty() && it.languageTag.equals(language, ignoreCase = true)
            } ?: SYSTEM
        }
    }
}
