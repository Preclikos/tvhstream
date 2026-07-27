package cz.preclikos.tvhstream.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun chooserLanguages_mapToAndroidLanguageTags() {
        assertEquals("", AppLanguage.SYSTEM.languageTag)
        assertEquals("de", AppLanguage.GERMAN.languageTag)
        assertEquals("en", AppLanguage.ENGLISH.languageTag)
        assertEquals("cs", AppLanguage.CZECH.languageTag)
    }

    @Test
    fun currentLanguageTag_mapsToChooserLanguage() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags(""))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromLanguageTags("de"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromLanguageTags("de-DE"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-GB"))
        assertEquals(AppLanguage.CZECH, AppLanguage.fromLanguageTags("cs-CZ"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTags("fr"))
    }
}
