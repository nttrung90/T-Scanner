package android.net

import android.os.Parcel

class TestUri(private val uriString: String) : Uri() {
    override fun isHierarchical(): Boolean = true
    override fun isOpaque(): Boolean = false
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = "content"
    override fun getSchemeSpecificPart(): String = uriString
    override fun getEncodedSchemeSpecificPart(): String = uriString
    override fun getAuthority(): String = "media"
    override fun getEncodedAuthority(): String = "media"
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String = "media"
    override fun getPort(): Int = -1
    override fun getPath(): String = uriString
    override fun getEncodedPath(): String = uriString
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = listOf(uriString)
    override fun getLastPathSegment(): String = uriString
    override fun buildUpon(): Builder? = null
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {}
    override fun toString(): String = uriString
    override fun compareTo(other: Uri): Int = uriString.compareTo(other.toString())

    companion object {
        fun create(uriString: String): Uri = TestUri(uriString)
    }
}
