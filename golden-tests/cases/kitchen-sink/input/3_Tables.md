# Tables

## Alignment and formatting

Every column alignment, and every inline construct inside cells:

| Left | Center | Right | Default |
| :--- | :----: | ----: | --- |
| `code` | **bold** | *emphasis* | ~~struck~~ |
| [a link](https://h5p.org) | ++inserted++ | math $\beta$ | a footnote[^cell] |
| a \| escaped pipe | <em>raw html</em> | 1 | |

## Pseudo table

Four columns and an image, so the whole table is rebuilt out of `H5P.Row`:

| Name | Picture | Note | Math |
| :--- | :---: | ---: | --- |
| Cat | before ![A cat](cell.png) after | **loud** | $\gamma$ |
| Dog | no picture here | quiet | $\delta$ |
| Fish | | silent | |

## Wide table

Five columns and an image, which is one too many for a row, so it stays a real table and
the image is committed after it:

| A | B | C | D | E |
| --- | --- | --- | --- | --- |
| 1 | 2 | ![Too wide](cell.png) | 4 | 5 |

## Header only

| Only | A | Header |
| --- | --- | --- |

## A table inside a footnote inside a quote

> The quote refers to a note whose body is itself a table[^quoted].

Text after everything, to prove the chapter resumes.

[^cell]: A footnote defined after the table that references it.

[^quoted]: The body of this note holds a table:

    | Year | Value |
    | --- | ----: |
    | 2024 | 12 |
    | 2025 | 15 |

    and a closing remark after it.
