package sample

import fuse.*
import kotlinx.cinterop.*
import platform.posix.*

open class FuseFS {
    data class Stat(
        val isDir: Boolean = false,
        val isFile: Boolean = false,
        val nlinks: Int = 1,
        val size: Long = 0L
    )

    open class FuseException(val code: Int) : Throwable()
    data class NotFound(val path: String = "") : FuseException(-ENOENT)
    data class Unauthorized(val path: String = "") : FuseException(-EACCES)

    fun notFound(path: String = ""): Nothing = throw NotFound(path)
    fun unauthorized(path: String = ""): Nothing = throw Unauthorized(path)

    open fun getattr(path: String): Stat {
        throw NotFound(path)
    }

    companion object {
        fun runFS(args: Array<String>, fs: FuseFS) {
            memScoped {
                val ops = alloc<fuse_operations>().also { ops ->
                    memset(ops.ptr, 0, fuse_operations.size.convert())
                    //ops.statfs = staticCFunction(::hello_statfs)
                    ops.getattr = staticCFunction(::hello_getattr)
                    ops.open = staticCFunction(::hello_open)
                    ops.read = staticCFunction(::hello_read)
                    ops.readdir = staticCFunction(::hello_readdir)

                }

                println("[1]")
                println("[1]: ${ops.getattr}")
                println("[1]: ${ops.open}")
                argcv(args) { argc, argv ->
                    println("[2]")
                    val ref = StableRef.create(fs)
                    val result = try {
                        fuse_main_real(argc, argv, ops.ptr, fuse_operations.size.convert(), ref.asCPointer())
                    } finally {
                        ref.dispose()
                    }
                    println("[3] -> $result")
                }
            }
        }
    }
}

internal val currentFuseFS: FuseFS get() = fuse_get_context()!!.pointed.private_data!!.asStableRef<FuseFS>().get()

inline fun <T> argcv(pargs: Array<String>, callback: (argc: Int, argv: CValuesRef<CPointerVar<ByteVar>>) -> T): T {
    val args = arrayOf("program", *pargs)
    memScoped {
        val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
        for ((index, arg) in args.withIndex()) {
            argv[index] = arg.cstr.ptr
        }
        argv[args.size] = null
        return callback(args.size, argv)
    }
}

val file_path = "/hello.txt"
val file_content = "Hello World!\n".encodeToByteArray()
val file_size = file_content.size

//fun hello_statfs(path: CPointer<ByteVar>?, buf: CPointer<statvfs>?) {
//    buf!!.pointed.
//}

inline fun fuseBody(callback: () -> Unit): Int {
    initRuntimeIfNeeded()
    return try {
        callback()
        0
    } catch (e: FuseFS.FuseException) {
        e.code
    } catch (e: Throwable) {
        -1
    }
}

fun hello_getattr(path: CPointer<ByteVar>?, stbuf: CPointer<stat>?): Int {
    return fuseBody {
        memset(stbuf, 0, stat.size.convert())
        val rpath = path?.toKString() ?: "/"
        val stat = currentFuseFS.getattr(rpath)
        val stbufp = stbuf?.pointed
        if (stbufp != null) {
            stbufp.st_mode = 0.convert()
            if (stat.isDir) stbufp.st_mode = (stbufp.st_mode.toInt() or S_IFDIR or "755".toInt(8)).convert()
            if (stat.isFile) stbufp.st_mode = (stbufp.st_mode.toInt() or S_IFREG or "444".toInt(8)).convert()
            stbufp.st_size = stat.size.convert()
            stbufp.st_nlink = stat.nlinks.convert()
        }
    }
}

fun hello_open(path: CPointer<ByteVar>?, fi: CPointer<fuse_file_info>?): Int {
    return fuseBody {

        val rpath = path!!.toKString()

        println("hello_open $rpath")

        if (rpath != file_path) /* We only recognize one file. */
            return -ENOENT

        if ((fi!!.pointed.flags and O_ACCMODE) != O_RDONLY) /* Only reading allowed. */
            return -EACCES
    }
}

fun hello_readdir(
    path: CPointer<ByteVar>?,
    buf: COpaquePointer?,
    filler: fuse_fill_dir_t?,
    offset: off_t,
    fuse_file_info: CPointer<fuse_file_info>?
): Int {
    initRuntimeIfNeeded()

    val rpath = path!!.toKString()

    println("hello_readdir $rpath")

    if (rpath != "/") /* We only recognize the root directory. */
        return -ENOENT

    memScoped {
        filler!!(buf, ".".cstr.ptr, null, 0.convert())           /* Current directory (.)  */
        filler!!(buf, "..".cstr.ptr, null, 0.convert())          /* Parent directory (..)  */
        filler!!(buf, file_path.cstr.ptr + 1, null, 0.convert()) /* The only file we have. */
    }

    return 0
}

fun hello_read(path: CPointer<ByteVar>?, buf: CPointer<ByteVar>?, size: size_t, offset: off_t, fi: CPointer<fuse_file_info>?): Int {
    initRuntimeIfNeeded()

    val rpath = path!!.toKString()

    println("hello_read $rpath")

    if (rpath != file_path)
        return -ENOENT

    if (offset >= file_size) /* Trying to read past the end of file. */
        return 0

    var rsize = size.toLong()

    if (offset.toLong() + size.toLong() > file_size.toLong()) /* Trim the read to the file size. */
        rsize = file_size - offset

    memScoped {
        file_content.usePinned { file_content_pinned ->
            memcpy(buf, file_content_pinned.addressOf(offset.toInt()), rsize.convert()) /* Provide the content. */
        }
    }

    return rsize.convert()
}
