package app.zxtune.fs.provider

import android.content.ContentProvider
import android.os.CancellationSignal
import android.os.OperationCanceledException
import app.zxtune.fs.*
import app.zxtune.utils.AsyncWorker
import app.zxtune.utils.ProgressCallback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController
import java.io.IOException

// tests both Provider and VfsClient
@RunWith(RobolectricTestRunner::class)
class ClientProviderTest {

    private val fastDirContent = Array(10) { TestDir(2 + it) }
    private val fastDir = object : TestDir(1) {
        override fun enumerate(visitor: VfsDir.Visitor) = fastDirContent.forEach(visitor::onDir)

        override val parent: VfsObject? = null
    }
    private val fastUri = fastDir.uri

    private val slowDirContent = Array(5) { TestFile(11 + it, "Unused") }
    private val slowDir = object : TestDir(10) {
        override fun enumerate(visitor: VfsDir.Visitor) {
            slowDirContent.forEachIndexed { index, obj ->
                kotlin.runCatching { Thread.sleep(1200) }
                visitor.onProgressUpdate(index + 1, slowDirContent.size)
                visitor.onFile(obj)
            }
        }

        override val parent = fastDir
    }
    private val slowUri = slowDir.uri

    private val deepDir = object : TestDir(100) {
        override fun enumerate(visitor: VfsDir.Visitor) = Unit
        override val parent = slowDir
    }
    private val deepUri = deepDir.uri

    private val failedDir = object : TestDir(1000) {
        override fun enumerate(visitor: VfsDir.Visitor) {
            throw IOException("Failed to enumerate")
        }
    }
    private val failedUri = failedDir.uri

    private val hangingDir = object : TestDir(10000) {
        override fun enumerate(visitor: VfsDir.Visitor) {
            Thread.sleep(10000000L)
        }
    }
    private val hangingUri = hangingDir.uri

    private val unknownUri = fastDirContent.first().uri

    private val resolver = mock<Resolver> {
        on { resolve(fastUri) } doReturn fastDir
        on { resolve(slowUri) } doReturn slowDir
        on { resolve(deepUri) } doReturn deepDir
        on { resolve(failedUri) } doReturn failedDir
        on { resolve(hangingUri) } doReturn hangingDir
        on { resolve(eq(fastUri), any()) } doReturn fastDir
        on { resolve(eq(slowUri), any()) } doAnswer {
            val progress = it.getArgument<ProgressCallback>(1)
            for (idx in 10..50 step 10) {
                Thread.sleep(1100)
                progress.onProgressUpdate(idx, 50)
            }
            slowDir
        }
        on { resolve(eq(failedUri), any()) } doThrow Error("Failed to resolve")
    }
    private val schema = mock<SchemaSource> {
        on { resolved(any()) } doAnswer {
            when (val arg = it.getArgument<VfsObject>(0)) {
                is VfsDir -> Schema.Listing.Dir(arg.uri, arg.name, arg.description, null, false)
                is VfsFile -> Schema.Listing.File(
                    arg.uri,
                    arg.name,
                    arg.description,
                    arg.size,
                    null,
                    null
                )
                else -> null
            }
        }
        on { parents(any()) } doAnswer {
            it.getArgument<List<VfsObject>>(0).map { obj ->
                Schema.Parents.Object(obj.uri, obj.name, null)
            }
        }
        on { directories(any()) } doAnswer {
            it.getArgument<List<VfsDir>>(0).map { dir -> mock.resolved(dir) as Schema.Listing.Dir }
        }
        on { files(any()) } doAnswer {
            it.getArgument<List<VfsFile>>(0)
                .map { file -> mock.resolved(file) as Schema.Listing.File }
        }
    }

    private lateinit var provider: ContentProvider
    private lateinit var client: VfsProviderClient
    private val listingCallback = mock<VfsProviderClient.ListingCallback>()
    private val parentsCallback = mock<VfsProviderClient.ParentsCallback>()

    @Before
    fun setUp() {
        provider = ContentProviderController.of(Provider(resolver, schema)).create().get()
        client = VfsProviderClient(provider.context!!)
        reset(listingCallback, parentsCallback)
    }

    @After
    fun tearDown() = verifyNoMoreInteractions(listingCallback, parentsCallback)

    @Test
    fun `resolve unknown`() {
        client.resolve(unknownUri, listingCallback)
    }

    @Test
    fun `resolve failed`() {
        val ex = assertThrows<Exception> {
            client.resolve(failedUri, listingCallback)
        }
        assertEquals("Failed to resolve", ex.message)
    }

    @Test
    fun `resolve fast`() {
        client.resolve(fastUri, listingCallback)
        verify(listingCallback).onDir(fastDir.uri, fastDir.name, fastDir.description, null, false)
    }

    @Test
    fun `resolve slow`() {
        client.resolve(slowUri, listingCallback)
        inOrder(listingCallback) {
            // dump progress first
            verify(listingCallback, times(5)).onProgress(any(), eq(50))
            verify(listingCallback).onDir(
                slowDir.uri,
                slowDir.name,
                slowDir.description,
                null,
                false
            )
        }
    }

    @Test
    fun `list unknown`() {
        client.list(unknownUri, listingCallback)
    }

    @Test
    fun `list failed`() {
        val ex = assertThrows<Exception> {
            client.list(failedUri, listingCallback)
        }
        assertEquals("Failed to enumerate", ex.message)
    }

    @Test
    fun `list fast`() {
        client.list(fastUri, listingCallback)
        fastDirContent.forEach {
            verify(listingCallback).onDir(it.uri, it.name, it.description, null, false)
        }
    }

    @Test
    fun `list slow`() {
        client.list(slowUri, listingCallback)
        val elements = slowDirContent.size
        inOrder(listingCallback) {
            // dump progress first
            for (done in 1..elements) {
                verify(listingCallback).onProgress(done, elements)
            }
            slowDirContent.forEach {
                verify(listingCallback).onFile(it.uri, it.name, it.description, it.size, null, null)
            }
        }
    }

    @Test
    fun `list empty`() {
        client.list(deepUri, listingCallback)
    }

    @Test
    fun `parents chain`() {
        client.parents(deepUri, parentsCallback)
        inOrder(parentsCallback) {
            verify(parentsCallback).onObject(fastDir.uri, fastDir.name, null)
            verify(parentsCallback).onObject(slowDir.uri, slowDir.name, null)
            verify(parentsCallback).onObject(deepDir.uri, deepDir.name, null)
        }
    }

    @Test
    fun `parents empty`() {
        client.parents(fastUri, parentsCallback)
        verify(parentsCallback).onObject(fastDir.uri, fastDir.name, null)
    }

    @Test
    fun `search slow`() {
        client.search(slowUri, "object", listingCallback)
        slowDirContent.forEach {
            verify(listingCallback).onFile(it.uri, it.name, it.description, it.size, null, null)
        }
    }

    @Test
    fun `search empty`() {
        client.search(deepUri, "object", listingCallback)
    }

    @Test
    fun `client exception`() {
        listingCallback.stub {
            on { onProgress(any(), any()) } doThrow Error("Client cancellation")
        }
        val ex = assertThrows<Exception> {
            client.list(slowUri, listingCallback)
        }
        assertEquals(OperationCanceledException().message, ex.message)
        verify(listingCallback).onProgress(any(), any())
    }

    @Test
    fun `client cancellation`() {
        val signal = CancellationSignal()
        listingCallback.stub {
            on { onProgress(any(), any()) } doAnswer {
                signal.cancel()
            }
        }
        val ex = assertThrows<Exception> {
            client.list(slowUri, listingCallback, signal)
        }
        assertEquals(OperationCanceledException().message, ex.message)
        verify(listingCallback).onProgress(any(), any())
    }

    @Test
    fun `client interruption`() {
        val signal = CancellationSignal()
        AsyncWorker("unused").execute {
            Thread.sleep(1000)
            signal.cancel()
        }
        val ex = assertThrows<Exception> {
            client.list(hangingUri, listingCallback, signal)
        }
        assertEquals("sleep interrupted", ex.message)
    }
}