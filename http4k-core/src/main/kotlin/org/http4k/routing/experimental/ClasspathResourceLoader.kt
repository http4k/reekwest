package org.http4k.routing.experimental

import org.http4k.core.ContentType
import org.http4k.core.MimeTypes
import java.net.URL
import java.time.Instant

data class ClasspathResourceLoader(
    val basePackagePath: String,
    val mimeTypes: MimeTypes = MimeTypes(),
    val lastModifiedFinder: (path: String) -> Instant?
) : NewResourceLoader {

    override fun invoke(path: String): Resource? {
        val resourcePath = basePackagePath.withLeadingSlash().pathJoin(path.orIndexFile())
        return javaClass.getResource(resourcePath)?.toResource(mimeTypes.forFile(resourcePath), lastModifiedFinder(resourcePath))
    }
}

private fun URL.toResource(contentType: ContentType, lastModified: Instant?) = URLResource(this, contentType, lastModified)