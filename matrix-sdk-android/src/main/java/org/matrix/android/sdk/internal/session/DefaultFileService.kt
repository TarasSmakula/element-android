/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import arrow.core.Try
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.util.Cancelable
import org.matrix.android.sdk.api.util.NoOpCancellable
import org.matrix.android.sdk.internal.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.internal.crypto.attachments.MXEncryptedAttachments
import org.matrix.android.sdk.internal.di.SessionDownloadsDirectory
import org.matrix.android.sdk.internal.di.UnauthenticatedWithCertificateWithProgress
import org.matrix.android.sdk.internal.session.download.DownloadProgressInterceptor.Companion.DOWNLOAD_PROGRESS_INTERCEPTOR_HEADER
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.util.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.util.toCancelable
import org.matrix.android.sdk.internal.util.writeToFile
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import javax.inject.Inject

internal class DefaultFileService @Inject constructor(
        private val context: Context,
        @SessionDownloadsDirectory
        private val sessionCacheDirectory: File,
        private val contentUrlResolver: ContentUrlResolver,
        @UnauthenticatedWithCertificateWithProgress
        private val okHttpClient: OkHttpClient,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val taskExecutor: TaskExecutor
) : FileService {

    private fun String.safeFileName() = URLEncoder.encode(this, Charsets.US_ASCII.displayName())

    // Folder to store downloaded file (not decrypted)
    private val legacyFolder = File(sessionCacheDirectory, "MF")
    private val downloadFolder = File(sessionCacheDirectory, "F")
    private val decryptedFolder = File(downloadFolder, "D")

    init {
        // Clear the legacy downloaded files
        legacyFolder.deleteRecursively()
    }

    /**
     * Retain ongoing downloads to avoid re-downloading and already downloading file
     * map of mxCurl to callbacks
     */
    private val ongoing = mutableMapOf<String, ArrayList<MatrixCallback<File>>>()

    /**
     * Download file in the cache folder, and eventually decrypt it
     * TODO looks like files are copied 3 times
     */
    override fun downloadFile(id: String,
                              fileName: String,
                              mimeType: String?,
                              url: String?,
                              elementToDecrypt: ElementToDecrypt?,
                              callback: MatrixCallback<File>): Cancelable {
        val unwrappedUrl = url ?: return NoOpCancellable.also {
            callback.onFailure(IllegalArgumentException("url is null"))
        }

        Timber.v("## FileService downloadFile $unwrappedUrl")

        synchronized(ongoing) {
            val existing = ongoing[unwrappedUrl]
            if (existing != null) {
                Timber.v("## FileService downloadFile is already downloading.. ")
                existing.add(callback)
                return NoOpCancellable
            } else {
                // mark as tracked
                ongoing[unwrappedUrl] = ArrayList()
                // and proceed to download
            }
        }

        return taskExecutor.executorScope.launch(coroutineDispatchers.main) {
            withContext(coroutineDispatchers.io) {
                Try {
                    if (!decryptedFolder.exists()) {
                        decryptedFolder.mkdirs()
                    }
                    // ensure we use unique file name by using URL (mapped to suitable file name)
                    // Also we need to add extension for the FileProvider, if not it lot's of app that it's
                    // shared with will not function well (even if mime type is passed in the intent)
                    File(downloadFolder, fileForUrl(unwrappedUrl, mimeType))
                }.flatMap { destFile ->
                    if (!destFile.exists()) {
                        val resolvedUrl = contentUrlResolver.resolveFullSize(url) ?: return@flatMap Try.Failure(IllegalArgumentException("url is null"))

                        val request = Request.Builder()
                                .url(resolvedUrl)
                                .header(DOWNLOAD_PROGRESS_INTERCEPTOR_HEADER, url)
                                .build()

                        val response = try {
                            okHttpClient.newCall(request).execute()
                        } catch (e: Throwable) {
                            return@flatMap Try.Failure(e)
                        }

                        if (!response.isSuccessful) {
                            return@flatMap Try.Failure(IOException())
                        }

                        val source = response.body?.source()
                                ?: return@flatMap Try.Failure(IOException())

                        Timber.v("Response size ${response.body?.contentLength()} - Stream available: ${!source.exhausted()}")

                        // Write the file to cache (encrypted version if the file is encrypted)
                        writeToFile(source.inputStream(), destFile)
                        response.close()
                    } else {
                        Timber.v("## FileService: cache hit for $url")
                    }

                    Try.just(destFile)
                }
            }.flatMap { downloadedFile ->
                // Decrypt if necessary
                if (elementToDecrypt != null) {
                    val decryptedFile = File(decryptedFolder, fileForUrl(unwrappedUrl, mimeType))

                    if (!decryptedFile.exists()) {
                        Timber.v("## FileService: decrypt file")
                        val decryptSuccess = decryptedFile.outputStream().buffered().use { outputStream ->
                            downloadedFile.inputStream().use { inputStream ->
                                MXEncryptedAttachments.decryptAttachment(
                                        inputStream,
                                        elementToDecrypt,
                                        outputStream
                                )
                            }
                        }
                        if (!decryptSuccess) {
                            return@flatMap Try.Failure(IllegalStateException("Decryption error"))
                        }
                    } else {
                        Timber.v("## FileService: cache hit for decrypted file")
                    }
                    Try.just(decryptedFile)
                } else {
                    // Clear file
                    Try.just(downloadedFile)
                }
            }.fold(
                    { throwable ->
                        callback.onFailure(throwable)
                        // notify concurrent requests
                        val toNotify = synchronized(ongoing) {
                            ongoing[unwrappedUrl]?.also {
                                ongoing.remove(unwrappedUrl)
                            }
                        }
                        toNotify?.forEach { otherCallbacks ->
                            tryOrNull { otherCallbacks.onFailure(throwable) }
                        }
                    },
                    { file ->
                        callback.onSuccess(file)
                        // notify concurrent requests
                        val toNotify = synchronized(ongoing) {
                            ongoing[unwrappedUrl]?.also {
                                ongoing.remove(unwrappedUrl)
                            }
                        }
                        Timber.v("## FileService additional to notify ${toNotify?.size ?: 0} ")
                        toNotify?.forEach { otherCallbacks ->
                            tryOrNull { otherCallbacks.onSuccess(file) }
                        }
                    }
            )
        }.toCancelable()
    }

    fun storeDataFor(url: String, mimeType: String?, inputStream: InputStream) {
        val file = File(downloadFolder, fileForUrl(url, mimeType))
        val source = inputStream.source().buffer()
        file.sink().buffer().let { sink ->
            source.use { input ->
                sink.use { output ->
                    output.writeAll(input)
                }
            }
        }
    }

    private fun fileForUrl(url: String, mimeType: String?): String {
        val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) }
        return if (extension != null) "${url.safeFileName()}.$extension" else url.safeFileName()
    }

    override fun isFileInCache(mxcUrl: String?, mimeType: String?, elementToDecrypt: ElementToDecrypt?): Boolean {
        return fileState(mxcUrl, mimeType, elementToDecrypt) == FileService.FileState.IN_CACHE
    }

    private fun getClearFile(mxcUrl: String, mimeType: String?, elementToDecrypt: ElementToDecrypt?): File {
        return if (elementToDecrypt == null) {
            // Clear file
            File(downloadFolder, fileForUrl(mxcUrl, mimeType))
        } else {
            // Encrypted file
            File(decryptedFolder, fileForUrl(mxcUrl, mimeType))
        }
    }

    override fun fileState(mxcUrl: String?, mimeType: String?, elementToDecrypt: ElementToDecrypt?): FileService.FileState {
        mxcUrl ?: return FileService.FileState.UNKNOWN
        if (getClearFile(mxcUrl, mimeType, elementToDecrypt).exists()) return FileService.FileState.IN_CACHE
        val isDownloading = synchronized(ongoing) {
            ongoing[mxcUrl] != null
        }
        return if (isDownloading) FileService.FileState.DOWNLOADING else FileService.FileState.UNKNOWN
    }

    /**
     * Use this URI and pass it to intent using flag Intent.FLAG_GRANT_READ_URI_PERMISSION
     * (if not other app won't be able to access it)
     */
    override fun getTemporarySharableURI(mxcUrl: String?, mimeType: String?, elementToDecrypt: ElementToDecrypt?): Uri? {
        mxcUrl ?: return null
        // this string could be extracted no?
        val authority = "${context.packageName}.mx-sdk.fileprovider"
        val targetFile = getClearFile(mxcUrl, mimeType, elementToDecrypt)
        if (!targetFile.exists()) return null
        return FileProvider.getUriForFile(context, authority, targetFile)
    }

    override fun getCacheSize(): Int {
        return downloadFolder.walkTopDown()
                .onEnter {
                    Timber.v("Get size of ${it.absolutePath}")
                    true
                }
                .sumBy { it.length().toInt() }
    }

    override fun clearCache() {
        downloadFolder.deleteRecursively()
    }

    override fun clearDecryptedCache() {
        decryptedFolder.deleteRecursively()
    }
}
