---
title: Front matter is parsed and thrown away
tags: [kitchen, sink]
---

# Overview

Inline, all at once: *emphasis*, **strong**, ***both***, `code`, ~~struck~~, ++inserted++,
an autolink <https://h5p.org>, an [external link](https://h5p.org "Hover title"), an
[anchor into this chapter](#lists-carrying-blocks), a [link to chapter 3](3_Tables.md), a
[link into chapter 3](3_Tables.md#alignment-and-formatting), a wiki link [[Notes]], a
labelled one [[Notes|the closing notes]], a sectioned one [[Notes#Reused And Chained]], a
dead one [[Nowhere]], a link to a file that is not a chapter [here](CHANGELOG.md), and math
in the two spellings that count, $a^2$ and $$b^2$$, while the backslash forms \(c^2\) and \[d^2\]
stay literal. Money and shell variables stay text: $5 and $10 more, and $PATH.

Escapes and entities: \*not emphasis\*, a literal \$dollar\$, `a < b`, and an ampersand &
in running text.

## Headings shift down and clamp

### Third level

#### Fourth level clamps to h3

##### So does the fifth

## Lists carrying blocks

1. An ordered item with a fenced block:

   ```kotlin
   fun main() = println("hi")
   ```

2. An item with a table in it:

   | Key | Value |
   | --- | ----: |
   | one | 1 |

3. An item with an image in it: ![Inline icon](pic.png)

   And a second paragraph, which makes this list loose.

- [x] A finished task, with a footnote[^task]
- [ ] An unfinished one containing `code` and *emphasis*
  - A nested bullet
    - and a deeper one, carrying math $x_i$ and an image ![Deep](assets/deep.png)

## Raw HTML that survives

A paragraph with <strong>allowed</strong> and <u>disallowed</u> markup, plus an
<span style="color:#1768c4">inline span</span>.

<blockquote>A hand-written blockquote, which H5P removes while keeping this text.</blockquote>

---

Text after a thematic break, to close the chapter.

[^task]: A footnote referenced from a task list item.
