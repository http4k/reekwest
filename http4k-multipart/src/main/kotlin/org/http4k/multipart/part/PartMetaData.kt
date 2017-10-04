package org.http4k.multipart.part

internal abstract class PartMetaData(val fieldName: String?, val isFormField: Boolean, val contentType: String?, val fileName: String?, val headers: Map<String, String>)
