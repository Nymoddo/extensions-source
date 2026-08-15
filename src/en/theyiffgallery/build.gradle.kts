import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    // >>> CAMBIAR: nombre de tu extensión
    name = "TheYiffGallery"
    versionCode = 1
    // >>> CAMBIAR: SAFE, MIXED o NSFW según el contenido del sitio
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        // >>> CAMBIAR: idioma y dominio del sitio
        lang = "en"
        baseUrl = "https://theyiffgallery.com"
    }
}
