package at.jku.ssw.h5p.converter.render

import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text

/** The text of a node with all markup dropped - used for alt texts, heading ids and chapter titles. */
fun plainTextOf(node: Node): String {
    val sb = StringBuilder()
    var current = node.firstChild
    while (current != null) {
        when (current) {
            is Text -> sb.append(current.literal)
            is Code -> sb.append(current.literal)
            is SoftLineBreak -> sb.append('\n')
            is HardLineBreak -> sb.append('\n')
            else -> sb.append(plainTextOf(current))
        }
        current = current.next
    }
    return sb.toString()
}

/** The direct children of [node] that are a [T] - the commonmark AST offers no such accessor. */
inline fun <reified T : Node> childrenOfType(node: Node): List<T> {
    val found = mutableListOf<T>()
    var child = node.firstChild
    while (child != null) {
        if (child is T) found.add(child)
        child = child.next
    }
    return found
}

/** Whether [node] contains a [T] anywhere below it. */
inline fun <reified T : Node> containsType(node: Node): Boolean = containsType(node, T::class.java)

/** The recursion behind [containsType] - a reified inline function cannot call itself. */
fun containsType(node: Node, type: Class<out Node>): Boolean {
    var child = node.firstChild
    while (child != null) {
        if (type.isInstance(child) || containsType(child, type)) return true
        child = child.next
    }
    return false
}
