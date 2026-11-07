# Prose

None of the dollars on this page is a formula.

The upgrade costs $5 and the extra module $10 more, so budget $15 in total.

Export $PATH and $HOME before running it.

A single escaped pair: \$notmath\$.

Inline code keeps its dollars: `$x$` and `$$y$$`.

```bash
echo "$PATH"
if [ -n "$1" ]; then echo "$1"; fi
```

Whitespace just inside the delimiters disqualifies it: $ x $ stays text.

An unclosed dollar $ on its own is text too, and so is a lone backslash \\ or a \[ that never closes.
