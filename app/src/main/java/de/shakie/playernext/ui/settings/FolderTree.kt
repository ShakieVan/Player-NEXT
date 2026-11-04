package de.shakie.playernext.ui.settings

/**
 * Folders that contain music, for the folder browser of "Musikordner". Built from the file
 * paths MediaStore knows; every folder counts the songs in it and below.
 */
class FolderTree private constructor(val roots: List<Node>) {
    class Node(val path: String, val name: String) {
        val children = ArrayList<Node>()
        var songs = 0
            internal set
        var parent: Node? = null
            internal set
    }

    /** Folder selection with "a selected folder covers everything below it". */
    class Selection(initial: Set<String>) {
        val paths = initial.map { it.trimEnd('/') }.toMutableSet()

        fun covered(path: String): Boolean = paths.any { path == it || path.startsWith("$it/") }
        fun coveredByParent(path: String): Boolean = paths.any { path.startsWith("$it/") }
        fun partly(path: String): Boolean = !covered(path) && paths.any { it.startsWith("$path/") }

        fun toggle(path: String) {
            if (path in paths) {
                paths -= path
            } else if (!coveredByParent(path)) {
                paths.removeAll { it.startsWith("$path/") }
                paths += path
            }
        }
    }

    companion object {
        /** Storage volumes: /storage/emulated/<user> and /storage/<SD card id>. */
        private val volume = Regex("^(/storage/emulated/\\d+|/storage/[^/]+)/")

        fun build(filePaths: Collection<String>, volumeName: (String) -> String): FolderTree {
            val nodes = HashMap<String, Node>()
            val roots = ArrayList<Node>()
            for (file in filePaths) {
                val m = volume.find(file)
                val rootPath = m?.groupValues?.get(1) ?: file.substringBeforeLast('/').substringBeforeLast('/').ifEmpty { "/" }
                var node = nodes.getOrPut(rootPath) { Node(rootPath, volumeName(rootPath)).also { roots += it } }
                node.songs++
                val dir = file.substringBeforeLast('/')
                if (dir.length <= rootPath.length) continue
                var path = rootPath
                for (part in dir.substring(rootPath.length + 1).split('/')) {
                    path = "$path/$part"
                    val parent = node
                    node = nodes.getOrPut(path) { Node(path, part).also { it.parent = parent; parent.children += it } }
                    node.songs++
                }
            }
            fun sort(n: Node) {
                n.children.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                n.children.forEach(::sort)
            }
            roots.forEach(::sort)
            roots.sortBy { if (it.path.startsWith("/storage/emulated/")) 0 else 1 }
            return FolderTree(roots)
        }
    }
}
