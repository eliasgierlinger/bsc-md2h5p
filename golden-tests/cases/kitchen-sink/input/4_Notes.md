# Notes

## Overview

A heading repeating chapter one's on purpose: both generate `overview`, and only the
`ch1-`/`ch4-` prefix keeps them apart. A wiki link back to it: [[Overview]].

## Reused And Chained

The same note twice[^a], and again[^a]; one that leads to another[^b]; and one whose body
is a small document of its own[^rich].

## Images that do not survive

A file that is not there: ![Gone](nope.png)

A type the platform rejects: ![Webp](broken.webp)

An image that is there, for contrast: ![Fine](pic.png)

## Raw HTML that does not balance

Deliberately the last thing in the book, because Moodle does not repair it and anything
after it could be swallowed:

<div>An open tag with no partner, and <em>an emphasis that never closes.

[^a]: Referenced twice; only the first reference carries the back-link anchor.

[^b]: This one references a third[^c].

[^c]: Reached only from another footnote's body, through the worklist.

[^rich]: A footnote body with everything in it:

    - a list item
    - another one, with `code`

    | In | A | Footnote |
    | --- | --- | --- |
    | table | inside | a note |

    ![Image inside a footnote](pic.png)

    > and a quote to close it out.
