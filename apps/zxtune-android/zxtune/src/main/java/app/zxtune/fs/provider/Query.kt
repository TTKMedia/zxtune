/**
 * @file
 * @brief VFS provider query helper
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.fs.provider

import android.content.ContentResolver
import android.content.UriMatcher
import android.net.Uri
import androidx.annotation.IntDef

/*
 * ${path} is full data uri (including subpath in fragment) stored as string
 *
 * content://app.zxtune.vfs/resolve/${path} - get object properties by full path
 * content://app.zxtune.vfs/listing/${path} - get directory content by full path
 * content://app.zxtune.vfs/parents/${path} - get object parents chain
 * content://app.zxtune.vfs/search/${path}?query=${query} - start search
 * content://app.zxtune.vfs/file/${path} - get information about and file object about local file
 */
internal object Query {
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(
        TYPE_RESOLVE, TYPE_LISTING, TYPE_PARENTS, TYPE_SEARCH, TYPE_FILE
    )
    annotation class Type

    const val TYPE_RESOLVE = 0
    const val TYPE_LISTING = 1
    const val TYPE_PARENTS = 2
    const val TYPE_SEARCH = 3
    const val TYPE_FILE = 4
    private const val AUTHORITY = "app.zxtune.vfs"
    private const val RESOLVE_PATH = "resolve"
    private const val LISTING_PATH = "listing"
    private const val PARENTS_PATH = "parents"
    private const val SEARCH_PATH = "search"
    private const val QUERY_PARAM = "query"
    private const val FILE_PATH = "file"
    private const val ITEM_SUBTYPE = "vnd.$AUTHORITY.item"
    private const val SIMPLE_ITEM_SUBTYPE = "vnd.$AUTHORITY.simple_item"
    private const val MIME_ITEM = "${ContentResolver.CURSOR_ITEM_BASE_TYPE}/$ITEM_SUBTYPE"
    private const val MIME_ITEMS_SET = "${ContentResolver.CURSOR_DIR_BASE_TYPE}/$ITEM_SUBTYPE"
    private const val MIME_SIMPLE_ITEMS_SET =
        "${ContentResolver.CURSOR_DIR_BASE_TYPE}/$SIMPLE_ITEM_SUBTYPE"
    private val uriTemplate = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "$RESOLVE_PATH/*", TYPE_RESOLVE)
        addURI(AUTHORITY, "$LISTING_PATH/*", TYPE_LISTING)
        addURI(AUTHORITY, "$PARENTS_PATH/*", TYPE_PARENTS)
        addURI(AUTHORITY, "$SEARCH_PATH/*", TYPE_SEARCH)
        addURI(AUTHORITY, "$FILE_PATH/*", TYPE_FILE)
    }

    //! @return Mime type of uri (used in content provider)
    @JvmStatic
    fun mimeTypeOf(uri: Uri) = when (uriTemplate.match(uri)) {
        TYPE_RESOLVE, TYPE_FILE -> MIME_ITEM
        TYPE_LISTING, TYPE_SEARCH -> MIME_ITEMS_SET
        TYPE_PARENTS -> MIME_SIMPLE_ITEMS_SET
        else -> throw IllegalArgumentException("Wrong URI: $uri")
    }

    @JvmStatic
    fun getPathFrom(uri: Uri): Uri = when (uriTemplate.match(uri)) {
        TYPE_RESOLVE, TYPE_LISTING, TYPE_PARENTS, TYPE_SEARCH, TYPE_FILE ->
            uri.pathSegments.getOrNull(1)?.let { Uri.parse(it) } ?: Uri.EMPTY
        else -> throw IllegalArgumentException("Wrong URI: $uri")
    }

    @JvmStatic
    fun getQueryFrom(uri: Uri) =
        uri.takeIf { uriTemplate.match(uri) == TYPE_SEARCH }?.getQueryParameter(QUERY_PARAM)
            ?: throw IllegalArgumentException("Wrong search URI: $uri")

    @JvmStatic
    @Type
    fun getUriType(uri: Uri) = uriTemplate.match(uri)

    @JvmStatic
    fun resolveUriFor(uri: Uri): Uri = makeUri(RESOLVE_PATH, uri).build()

    @JvmStatic
    fun listingUriFor(uri: Uri): Uri = makeUri(LISTING_PATH, uri).build()

    @JvmStatic
    fun parentsUriFor(uri: Uri): Uri = makeUri(PARENTS_PATH, uri).build()

    @JvmStatic
    fun searchUriFor(uri: Uri, query: String): Uri =
        makeUri(SEARCH_PATH, uri).appendQueryParameter(QUERY_PARAM, query).build()

    @JvmStatic
    fun fileUriFor(uri: Uri): Uri = makeUri(FILE_PATH, uri).build()

    private fun makeUri(path: String, uri: Uri) = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(AUTHORITY)
        .encodedPath(path)
        .appendPath(uri.toString())
}