package rxhttp

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rxhttp.wrapper.OkHttpCompat
import rxhttp.wrapper.await.AwaitImpl
import rxhttp.wrapper.cahce.CacheStrategy
import rxhttp.wrapper.callback.ProgressCallbackImpl
import rxhttp.wrapper.callback.SuspendProgressCallbackImpl
import rxhttp.wrapper.entity.Progress
import rxhttp.wrapper.parse.*
import java.io.IOException

/**
 * User: ljx
 * Date: 2020/3/21
 * Time: 23:56
 */
interface IRxHttp {

    @Throws(IOException::class)
    fun execute(): Response

    fun buildRequest(): Request

    //断点下载进度偏移量，进在带进度断点下载时生效
    val breakDownloadOffSize: Long
        get() = 0L

    fun getCacheStrategy(): CacheStrategy

    fun getOkHttpClient(): OkHttpClient
}

suspend fun IRxHttp.awaitBoolean(): Boolean = await()

suspend fun IRxHttp.awaitByte(): Byte = await()

suspend fun IRxHttp.awaitShort(): Short = await()

suspend fun IRxHttp.awaitInt(): Int = await()

suspend fun IRxHttp.awaitLong(): Long = await()

suspend fun IRxHttp.awaitFloat(): Float = await()

suspend fun IRxHttp.awaitDouble(): Double = await()

suspend fun IRxHttp.awaitString(): String = await()

suspend inline fun <reified T : Any> IRxHttp.awaitList(): List<T> = await()

suspend inline fun <reified K : Any, reified V : Any> IRxHttp.awaitMap(): Map<K, V> = await()

suspend fun IRxHttp.awaitBitmap(): Bitmap = await(BitmapParser())

suspend fun IRxHttp.awaitHeaders(): Headers = OkHttpCompat.headers(awaitOkResponse())

suspend fun IRxHttp.awaitOkResponse(): Response = await(OkResponseParser())

suspend inline fun <reified T : Any> IRxHttp.await(): T = await(object : SimpleParser<T>() {})

/**
 * @param destPath 本地存储路径
 * @param progress 进度回调
 */
suspend fun IRxHttp.awaitDownload(
    destPath: String,
    progress: ((Progress) -> Unit)? = null
): String {
    return toDownload(destPath, progress).await()
}

/**
 * @param destPath 本地存储路径
 * @param coroutine 用于开启一个协程，来控制进度回调所在的线程
 * @param progress 在suspend方法中回调，回调线程取决于协程所在线程
 */
suspend fun IRxHttp.awaitDownload(
    destPath: String,
    coroutine: CoroutineScope,
    progress: suspend (Progress) -> Unit
): String {
    return toDownload(destPath, coroutine, progress).await()
}

/**
 * 以上await方法，最终都会调用本方法
 */
suspend fun <T> IRxHttp.await(parser: Parser<T>): T = toParser(parser).await()

fun IRxHttp.toBoolean(): IAwait<Boolean> = toClass()

fun IRxHttp.toByte(): IAwait<Byte> = toClass()

fun IRxHttp.toShort(): IAwait<Short> = toClass()

fun IRxHttp.toInt(): IAwait<Int> = toClass()

fun IRxHttp.toLong(): IAwait<Long> = toClass()

fun IRxHttp.toFloat(): IAwait<Float> = toClass()

fun IRxHttp.toDouble(): IAwait<Double> = toClass()

fun IRxHttp.toStr(): IAwait<String> = toClass()

inline fun <reified T : Any> IRxHttp.toList(): IAwait<List<T>> = toClass()

inline fun <reified T : Any> IRxHttp.toMutableList(): IAwait<MutableList<T>> = toClass()

inline fun <reified K : Any, reified V : Any> IRxHttp.toMap(): IAwait<Map<K, V>> = toClass()

fun IRxHttp.toBitmap(): IAwait<Bitmap> = toParser(BitmapParser())

fun IRxHttp.toHeaders(): IAwait<Headers> = toOkResponse()
    .map {
        try {
            OkHttpCompat.headers(it)
        } finally {
            OkHttpCompat.closeQuietly(it)
        }
    }

fun IRxHttp.toOkResponse(): IAwait<Response> = toParser(OkResponseParser())

inline fun <reified T : Any> IRxHttp.toClass(): IAwait<T> = toParser(object : SimpleParser<T>() {})

/**
 * @param destPath 本地存储路径
 * @param progress 进度回调，在子线程回调
 */
fun IRxHttp.toDownload(
    destPath: String,
    progress: ((Progress) -> Unit)? = null
): IAwait<String> {
    var okHttpClient = getOkHttpClient()
    if (progress != null) {
        okHttpClient = HttpSender.clone(okHttpClient, ProgressCallbackImpl(breakDownloadOffSize, progress))
    }
    return toParser(DownloadParser(destPath), okHttpClient)
}

/**
 * @param destPath 本地存储路径
 * @param coroutine 用于开启一个协程，来控制进度回调所在的线程
 * @param progress 在suspend方法中回调，回调线程取决于协程所在线程
 */
fun IRxHttp.toDownload(
    destPath: String,
    coroutine: CoroutineScope,
    progress: suspend (Progress) -> Unit
): IAwait<String> {
    val clone = HttpSender.clone(getOkHttpClient(), SuspendProgressCallbackImpl(coroutine, breakDownloadOffSize, progress))
    return toParser(DownloadParser(destPath), clone)
}

fun <T> IRxHttp.toParser(
    parser: Parser<T>,
    client: OkHttpClient = getOkHttpClient()
): IAwait<T> = AwaitImpl(this, parser, client)
