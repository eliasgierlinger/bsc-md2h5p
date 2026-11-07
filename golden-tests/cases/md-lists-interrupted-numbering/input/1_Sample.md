# Sample

An item that continues after the block, so the resumed list repeats its number:

1. First
2. Second, with an image ![Icon](icon.png) and more text after it
3. Third

An item that ends with the block, so the next item is the first one after it:

1. First
2. Second, ending with a table:

   | Key | Value |
   | --- | --- |
   | one | 1 |

3. Third

A list that does not start at one:

5. Five
6. Six, with an image ![Icon](icon.png)
7. Seven

A bullet list has no numbering to lose, and must not grow a `start`:

- First
- Second, with an image ![Icon](icon.png)
- Third

An ordered list nested inside a bullet list, so only the inner one counts:

- Outer item
  1. Inner one
  2. Inner two, with an image ![Icon](icon.png)
  3. Inner three

Two ordered lists inside each other, so both have to resume from their own item:

1. Outer one
2. Outer two
   1. Inner one
   2. Inner two, with an image ![Icon](icon.png)
   3. Inner three
3. Outer three
