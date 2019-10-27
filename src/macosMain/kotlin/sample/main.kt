package sample

import fuse.*
import kotlinx.cinterop.*

fun main(args: Array<String>) = HelloFS.main(args)

class HelloFS : FuseFS() {
    override fun getattr(path: String): Stat {
        return when (path) {
            "/" -> Stat(isDir = true, nlinks = 3)
            file_path -> Stat(isFile = true, nlinks = 1)
            else -> notFound(path)
        }.also {
            println("HelloFS.getattr: '$path' : $it")
        }
    }

    companion object {
        fun main(args: Array<String>) {
            //ktfuse_sample_callback(staticCFunction { a ->
            //    a!!
            //    a.pointed.a = 10
            //    a.pointed.b = 20
            //    a.pointed.c = 30
            //    7
            //})
            runFS(args, HelloFS())
        }
    }
}

