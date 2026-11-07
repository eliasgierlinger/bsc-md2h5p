# Nesting

The hard cases: constructs that contain constructs that contain content blocks. Every
block below has to interrupt whatever encloses it and let it resume afterwards.

> A blockquote opening with prose[^inquote], then holding a table:
>
> | Cell | With |
> | --- | --- |
> | a footnote[^intable] | and math $\alpha$ |
>
> then an image in the same quote:
>
> ![Quoted image](pic.png)
>
> > A nested quote, one shade darker, with a list:
> >
> > - an item with `code`
> > - an item with an image ![Deep](assets/deep.png)
> >
> > > And a third level, holding its own table:
> > >
> > > | Depth | Three |
> > > | --- | --- |
> > > | still | a table |
>
> Back at the first level, after the nesting closed.

> [!NOTE]
> A plain note, with *emphasis* and a [link](https://h5p.org).

> [!WARNING]
> An alert containing a table with an image in it:
>
> | Name | Picture |
> | --- | --- |
> | Cat | ![A cat](cell.png) |
>
> and a footnote reference[^inalert] after the table.

> [!TIP]
> > A quote inside an alert, carrying a task list:
> >
> > - [x] done
> > - [ ] not done

> [!IMPORTANT]
> Two paragraphs, so the alert spans several blocks.
>
> This is the second one.

> [!CAUTION]
> The fifth and last alert type.

[^inquote]: Referenced from inside a blockquote.
[^intable]: Referenced from a table cell that is itself inside a blockquote.
[^inalert]: Referenced from inside an alert, and defined out here.
