# Formulas

Inline written as dollars: $E = mc^2$, which is the spelling Markdown editors know and the
one `H5P.MathDisplay` does not.

Display written as dollars, which both sides read, so it comes back out exactly as written:

$$\int_0^1 x^2\,dx = \frac{1}{3}$$

Spread over several lines, which is one paragraph and therefore one formula:

$$
\begin{aligned}
a &= b + c \\
d &= e - f
\end{aligned}
$$

Markdown must not touch what is inside: $x_1 * x_2$ keeps its underscore and its star, and
$p \$ q$ keeps its escaped dollar.

The backslash spellings are deliberately *not* math, because no Markdown editor renders
them as math either: \(a_1 + a_2\) and \[\sum_{i=1}^{n} i\] are ordinary escaped brackets
and come out as the literal characters the author asked for, leaving `MathDisplay` nothing
to convert.

| Symbol | Meaning |
| --- | --- |
| $\alpha$ | the first one |
| $\beta$ | the second one |

A formula in a footnote as well.[^note]

[^note]: The identity $e^{i\pi} + 1 = 0$.
